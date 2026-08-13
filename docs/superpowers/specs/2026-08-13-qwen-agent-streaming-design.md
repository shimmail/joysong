# Qwen Agent 端到端流式输出设计

## 目标

为 AI Agent 聊天实现 Qwen 到 Flutter 的端到端流式输出：DashScope 以 OpenAI-compatible SSE 返回增量文本，Kotlin 服务端解析并通过 SSE 转发，Flutter 在同一条临时 AI 消息中实时追加内容。只有完整生成、安全后处理和数据库事务全部成功后，才持久化 AI 消息并发送最终完成事件。

现有非流式 `POST /api/chat/sessions/{id}/messages` 保持不变，用于兼容旧客户端。新版 Flutter 默认使用流式端点。

## 非目标

- 不将 Spring MVC/JPA 应用迁移到 WebFlux。
- 不修改现有五个 `AI_AGENT_*` 环境变量契约。
- 不为意图识别、翻译或旧非流式聊天开启上游流式。
- 不保存上游中断时的残缺 AI 消息。
- 不对安全边界需要全文判定的 PLANNING 回答盲目逐 token 直出。

## 架构选择

使用 Spring MVC `SseEmitter` 作为对 Flutter 的 SSE 输出边界，使用现有 `RestTemplate` 的 streaming response callback 读取 DashScope 响应流。这一方案保持现有同步 MVC、JPA 和安全模型，避免引入第二套 reactive 线程与事务模型。

主要边界：

1. `AgentProviderRequestFactory` 显式区分流式主聊天和非流式请求。只有 Qwen `CHAT` 流式路径设置 `stream=true`。
2. 新增独立的 Qwen compatible SSE 解析器，不让 controller 或 `ChatService` 直接处理原始 JSON 行。
3. `ChatService` 编排 turn 生命周期、上游连接、安全后处理和最终持久化。
4. `ChatController` 只负责在请求线程捕获用户身份、进行建立流前校验，并将结构化事件写入 `SseEmitter`。
5. Flutter repository 负责 HTTP/SSE 协议解析，controller 只处理领域事件和界面状态。

## HTTP 与 SSE 协议

### 请求

```http
POST /api/chat/sessions/{id}/messages/stream
Authorization: Bearer <token>
Content-Type: application/json
Accept: text/event-stream
```

请求体沿用现有 `SendMessageRequest`，包括现有幂等键。响应类型为 `text/event-stream;charset=UTF-8`。

### 事件

SSE 使用固定事件名和 JSON data：

- `started`：流已建立，包含 `traceId`、`turnId` 以及服务端确认的用户消息。
- `delta`：包含本次新增的 `content`，客户端按收到顺序追加。
- `completed`：包含与旧接口对齐的完整 `ChatTurnResponse`。它是持久化成功的唯一确认。
- `error`：包含稳定公开错误码、`traceId` 和 `retryable`，不包含供应商响应体、异常 message、密钥或模型原名。

正常顺序为 `started` → 零个或多个 `delta` → `completed`。失败顺序为 `started` → 零个或多个 `delta` → `error`。每条流有且仅有一个终止事件。

## Qwen 上游处理

主聊天请求使用现有 DashScope compatible endpoint，请求体仅在流式路径添加 `stream=true`。意图模型仍使用 `stream=false`，以便在首个对用户可见的 token 前完成路由和安全分类。

SSE 解析器必须：

- 仅处理 `data:` 载荷，忽略心跳、空行和其他 SSE 字段。
- 从 `choices[].delta.content` 提取文本，允许空 delta。
- 识别 `[DONE]` 并要求正常终止。
- 使用 UTF-8 reader 正确处理多字节字符跨底层 read 边界。
- 对畸形 JSON、缺少正常终止和上游连接中断返回受控错误，不将原始载荷写日志。

## Turn 生命周期与持久化

服务端在发出 `started` 前完成身份、会话归属、请求校验和幂等开始操作。用户消息按现有 turn 模型保存，AI 消息只在下列条件全部满足时保存：

1. 上游流收到正常终止；
2. 完整文本通过现有自然语言和安全后处理；
3. turn 结果、catalog 附加信息和 AI 消息在一次最终完成操作中成功持久化。

数据库成功后才发送 `completed`。任何一步失败都将 turn 终结为失败状态，不保存残缺 AI 消息。不新增数据库迁移。

## 幂等、并发和重试

- 相同幂等键已成功：不再请求 Qwen，快速发送 `started` 和包含已持久化结果的 `completed`。
- 相同幂等键正在执行：在不启动第二条上游流的前提下返回稳定冲突错误。
- 上次执行失败：允许用同一条用户消息语义重试，不复用残缺 AI 文本。
- 单个 turn 只能执行一次完成持久化，并发回调通过原子状态转换保护。

Flutter 重试复用用户已输入的文本，但必须遵守服务端幂等契约，不在本地同时建立多条相同 turn 的流。

## 安全后处理

现有 `naturalizeUserFacingLanguage` 和 `enforcePlanningBoundary` 在完整响应上运行。流式实现不得绕过这些边界。

- 普通问答可实时发送上游 `delta`。收流完成后仍执行完整文本后处理，`completed` 中的正式消息是最终权威内容，Flutter 用它替换临时消息。
- PLANNING 回答首版不直出上游 token。后端完整缓冲、执行安全边界处理后，将安全结果作为单个 `delta` 发送，然后持久化并发送 `completed`。

日志继续遵循现有脱敏规则：不记录模型原名、provider response body、原始 SSE 载荷、用户文本或不受信任错误码。

## 断连、超时与资源释放

`SseEmitter` 设置明确超时。完成、错误、超时和客户端断开都进入同一个幂等清理路径：

- 关闭或取消 DashScope 连接和读取任务；
- 保证 turn 不遗留在 `RUNNING`；
- 不持久化残缺 AI 消息；
- 连接仍可写时发送一个 `error`，已断开时仅记录脱敏诊断信息。

SSE 响应头发出后不尝试改写 HTTP 状态或转入普通 JSON `ControllerAdvice`。可在 emitter 建立前发现的鉴权、归属和请求错误仍使用正常 HTTP 错误。

## Flutter 状态模型

Repository 新增一个返回领域事件 stream 的方法，封装认证 header、POST body、UTF-8/SSE 解析和连接取消。不让 presentation controller 解析原始 SSE 文本。

Controller 的状态转换：

1. 发送时立即插入用户消息和空的临时 AI 消息，状态为 sending。
2. `delta` 只追加到当前 turn 的临时 AI 消息，并通知 UI 刷新。
3. `completed` 用服务端正式消息和 turn 元数据替换临时消息，状态为 completed。
4. `error` 或未收到终止事件就断开时，保留已显示的临时文本，标记“生成中断，可重试”，不把它当作服务端已保存消息。
5. 重试时清理或替换旧临时 AI 消息，避免同一用户请求在列表中出现多个失败占位。
6. 页面销毁、切换会话或 controller dispose 时取消订阅，不再向已销毁状态发送更新。

新版 Flutter 默认使用流式方法，不在协议错误时静默回退到非流式接口，以免在未确定幂等结果时重复生成。旧客户端可继续使用旧接口。

## 测试策略

### 后端单元测试

- Request factory：Qwen CHAT 流式为 `true`；意图、翻译和旧聊天仍为 `false`；不根据模型名推断能力。
- SSE parser：多个 delta、空 delta、UTF-8 跨 read 边界、`[DONE]`、心跳/空行、畸形 JSON、上游 error 和无正常终止。
- 安全处理：PLANNING 不输出原始 delta；普通回答的 `completed` 内容为后处理后权威文本。

### 后端接口与集成测试

- MockMvc async/SSE 事件顺序、content type、鉴权、会话归属和建流前错误。
- 正常流只持久化一条完整 AI 消息，且 `completed` 发生在持久化之后。
- 供应商 4xx/5xx、超时、中途断流、畸形事件和客户端取消都不保存残缺 AI 消息，turn 不停留在 `RUNNING`。
- 已成功幂等重放不再调用 Qwen；运行中重复请求不建立第二条流；失败后可重试。
- 错误事件和日志不泄露上游响应体、原始代码、用户内容或模型名。

需要数据库的集成测试使用 worktree 隔离库 `myapp_worktree_qwen_agent_streaming`，迁移或测试前必须输出解析后的 host 和数据库名。

### Flutter 测试

- Repository SSE 解析：跨 chunk 边界、多行 data、UTF-8、事件顺序、无终止断开和订阅取消。
- Controller：delta 追加、completed 正式替换、失败保留临时文本、重试、并发防护、切换会话和 dispose。
- Widget：文本持续更新，中断状态显示“生成中断，可重试”，重试操作不重复插入用户消息。

## 交付与兼容性

后端先提供新 SSE 端点并保留旧端点，然后 Flutter 切换默认调用。因为旧端点不变，旧客户端无需同步升级。新端点的事件名和 data schema 作为稳定客户端契约，实现不向 Flutter 暴露 DashScope 原始事件格式。
