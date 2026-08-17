# JoySong AI Agent 开发文档

## 本次交付边界

本版本提供同步 REST 与 SSE 两种 Agent 工作流。新客户端使用 `POST /api/chat/sessions/{id}/messages/stream`，请求体与旧接口一致：

```json
{"content":"请介绍适合敏感肌的项目","idempotencyKey":"client-request-123"}
```

响应为 `text/event-stream`，事件严格使用以下四种名称与 JSON 数据：

```text
event: started
data: {"traceId":"trace-1","turnId":"turn-1","userMessage":{"id":"message-1","sessionId":"session-1","role":"USER","content":"请介绍适合敏感肌的项目","createdAt":"2026-08-13T10:00:00"}}

event: delta
data: {"content":"可以先关注"}

event: completed
data: {"turn":{"message":{"id":"message-2","sessionId":"session-1","role":"ASSISTANT","content":"可以先关注温和方案。","createdAt":"2026-08-13T10:00:01"},"catalogReport":null,"catalogItems":[],"intent":"CATALOG_QA","queryTarget":"PROJECT","nextAction":"NONE","traceId":"trace-1"}}

event: error
data: {"code":"AI_PROVIDER_TIMEOUT","traceId":"trace-1","retryable":true}
```

一次流只能以 `completed` 或 `error` 之一结束。原有 `POST /api/chat/sessions/{id}/messages` 同步接口及其请求/响应语义保持兼容，旧客户端无需改造；相同 session 中重复的有效 `idempotencyKey` 会按 `started` → `completed` 重放稳定结果，且不会新增 turn、消息或供应商调用。

每次成功工作流以 MySQL 中的一条 `agent_turns` 记录及其消息为边界。`summary_json` 是 `agent_sessions` 中服务器端持久化的紧凑结构化摘要，供上下文构建使用；当前 REST response 不暴露该字段。它不是可执行指令，也不保存完整模型提示词或原始敏感内容。

本版本明确不包含：LangChain4j、模型工具调用循环、自动记忆、trace 读取 API 或 `agent_tool_audits`。不应为这些未交付能力新增客户端调用、数据库查询或运行时依赖。

## 意图路由与模型配置

路由先检查当前请求的中英文关键词，并识别否定表达；只有当前信息不足时，才使用受限的近期上下文补全。仍无法确定时才调用模型解析。当前请求中已明确的意图或目录目标优先于历史，解析结果不能覆盖它。`SAFETY_SCREENING` 始终高于商业转接，当前消息中的明确否定也高于历史和模型结果。

意图解析器固定启用。生产环境的五项 Agent 变量全部必填：`AI_AGENT_MODEL` 仅用于最终回答，`AI_AGENT_INTENT_MODEL` 仅用于意图分类，两个模型 ID 必须分别配置。解析器与最终生成共享 `AI_AGENT_API_KEY`、`AI_AGENT_BASE_URL` 和 Provider 协议；两类客户端均固定直连，超时分别为 3 秒/8 秒和 10 秒/60 秒。

## 真人咨询确定性转接

明确的中英文真人咨询表达由本地高精度规则直接识别为 `HUMAN_CONSULTATION`；只有语义仍然歧义时才允许意图模型分类。该意图固定 `queryTarget=INSTITUTION`，模型不能提供咨询师或私聊用户 ID，也不能绕过安全、否定和目标校验。

候选机构须已验证、未删除，并且当前至少存在一名符合咨询师接口资格规则的咨询师。咨询师资格 ID 仅通过一次集合式查询取得；机构、城市和详情上下文的解析仍会使用各自所需的读取，不能将此表述为整体候选只进行一次仓储查询。结果依次按“当前请求点名机构、当前请求显式城市、当前请求未点名机构且未显式城市时可咨询的详情 context 机构、用户档案城市、全国”五级去重补齐，级内按评分降序、机构 ID 升序稳定排序，最多返回 4 家。

有候选时，服务端返回固定本地化文案，中文为“我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。”，英文为“I can help connect you with a real consultant. Choose an institution to see its currently available consultants.”，并设置 `nextAction=SELECT_INSTITUTION`。全国无候选时返回固定空态、空卡片列表和 `nextAction=NONE`。这两种结果都不调用回答模型。

## 数据模型与保留策略

Agent 使用且仅使用以下八张表：

- `agent_sessions`
- `agent_turns`
- `agent_messages`
- `agent_user_profiles`
- `agent_assessments`
- `agent_safety_events`
- `agent_plans`
- `agent_plan_items`

会话摘要保留在 MySQL；对话上下文只读取最近 20 条消息并限制在最近 7 天内。超过该窗口的消息不自动回灌为模型上下文。该限制是上下文读取策略，不等同于删除历史记录的作业。

真人咨询复用既有字符串与 JSON 元数据字段，不需要数据库迁移。历史消息只保存当轮机构卡片快照及最终 intent、target、action，不保存咨询师或私聊用户 ID。

## REST 语义

同步接口使用稳定的 HTTP 状态：成功响应为 `2xx`，输入不合法为 `400`，找不到资源为 `404`，同一请求冲突为 `409`，上游模型不可用或超时为 `503`，未预期服务端错误为 `500`。调用方应按 HTTP 状态处理结果，不能依赖错误文本匹配。流式接口建立 SSE 后，使用 `error` 事件携带稳定错误码、`traceId` 与可重试标记。

流式生成当前是 **Qwen-only**：只支持 `AI_AGENT_PROVIDER=qwen` 的百炼 OpenAI-compatible 流响应，不承诺其他 Provider 的流式格式兼容。`PLANNING` 意图不会向客户端发送上游 `delta`；服务端先完整缓冲模型输出，执行规划安全边界并完成持久化后，只通过 `completed` 交付最终安全内容。

`HUMAN_CONSULTATION` 的 REST 与 SSE 使用同一完成和持久化路径。SSE 只发送 `started` → `completed`，不发送回答模型 `delta`；相同幂等键会重放已持久化的固定文案和机构卡片快照，不重新调用回答模型。

若上游、客户端连接或最终化失败，已经展示在客户端的 partial assistant output 仅存在于客户端本地 UI，服务端不持久化该 partial 内容；turn 会以失败或取消状态结束，历史消息接口不会返回这段不完整回答。客户端可保留并标记本地 partial 消息，并按 `error.retryable` 决定是否允许重试。

创建 Agent turn 时请求体可选传递 `idempotencyKey`。同一 session 中重复的有效键返回已创建的稳定结果，不应额外创建 turn 或消息；未传该键的调用仍受支持。

## 结构化卡片导航契约

结构化卡片可以使用自身的目录 `id` 以及 `doctorId`、`projectId`、`institutionId` 打开医生、项目或机构详情。这些 ID 表示业务资源，**不能**当作私聊对端用户 ID。

真人咨询入口只在 `canChatWithHuman=true` 且能解析安全机构 ID 时显示：`INSTITUTION` 优先使用显式 `institutionId`，否则使用自身 `id`；`INSTITUTION_PROJECT` 只能使用显式 `institutionId`；`DOCTOR`、`PROJECT`、未知类型或空 ID 均不显示入口。机构、项目和医生资源 ID 永远不能直接成为私聊目标。

用户点击当前或历史机构卡片时，客户端都重新调用机构咨询师接口并展示当前可联系人员。只有该接口返回且由用户选中的 `BookingConsultant.id` 可以传给现有 DM 创建流程；被撤销或删除的历史咨询师不会从 Agent 历史恢复。首版继续复用通用 user-pair DM，不保存机构归属，也不在创建 DM 时原子复核机构成员关系。

## 可观测性与隐私

服务端只记录结构化且脱敏的日志，例如请求关联标识、session/turn 标识、模型标识、耗时、HTTP 状态和结果分类。不得记录 Authorization、API Key、完整提示词、完整回答或原始健康信息。排障通过受控日志与数据库运维流程完成；本版本没有 traces API。

## 验证与实验分支

详细命令见 [`AI_AGENT_TESTING.md`](./AI_AGENT_TESTING.md)。其中 MySQL 集成测试必须使用当前 worktree 的隔离数据库，不能连接共享开发数据库。

应用运行时固定启用 Agent。生产灰度与紧急停用由网关或发布平台控制入口：先进入维护窗口并停止全部旧写实例，再在同一冻结窗口完成 preflight、单实例 Flyway 和 readiness。自动 cohort 分流和指标聚合不由当前应用实现。

实现探索保存在 `codex/ai-agent-task3-spike` 分支。该 spike 用于保留实验记录，未合并到本次同步 REST 交付。
