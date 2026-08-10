# AI Agent 最小同步 REST 闭环设计

**日期：** 2026-08-09
**状态：** 已确认
**替代范围：** 本设计取代原 AI Agent V2 设计中尚未实施的 Task 3–6、LangChain4j 与完整 Flutter 扩展；已完成并通过评审的 V10 数据模型和 Turn 生命周期继续保留。

## 目标

以最小改动恢复一个可部署、可测试、可维护的同步 AI 聊天闭环：现有 `ChatService` 复用已有路由、目录检索、Prompt 和模型调用逻辑，但用 `TurnLifecycleService` 管理消息持久化、幂等、序号和终态，使外部模型调用不再处于数据库事务中。

本期只支持同步 REST。MySQL 继续作为会话、Turn、近期消息、结构化摘要和规划事实的唯一持久化来源。

## 明确不做

- 不引入 LangChain4j、LangGraph、Redis、向量数据库或自动记忆。
- 不引入通用 Agent 工具循环、Tool Registry 或开放式自治执行。
- 不拆分独立 PromptAssembler、Router 子系统或新的 ModelGateway 主链。
- 不继续本期的目录动态 JPQL 重写；性能优化等实际数据量和指标出现后再做。
- 不实现 SSE、有界流式执行器、断线重连或流式 terminal envelope。
- 不增加数据库 audit/run/step 表，也不增加 debug bundle。
- 不长期展示或分页加载完整历史消息。

## 当前基础

保留已完成能力：

- V10 只重建八张 `agent_*` 表。
- `agent_turns` 提供会话内序号、幂等键、请求哈希、单 RUNNING 约束和最小终态字段。
- `TurnLifecycleService` 提供短事务 begin/complete/fail/cancel、稳定重放和 `IDEMPOTENCY_EXPIRED`。
- `AgentContextBuilder` 提供只读成功回合上下文、结构化摘要、最多 20 条和最多 7 天的物理裁剪。
- 隔离 MySQL Testcontainers 已验证迁移、JPA、真实锁等待、并发、幂等和消息保留。

当前旧 `ChatService` 仍直接写两条默认序号为零的消息，并在事务中调用外部模型；本设计必须消除该不兼容路径。

## 同步聊天架构

```text
ChatController
  -> ChatService.sendMessage
  -> TurnLifecycleService.beginTurn        短事务
  -> AgentContextBuilder.load              短事务
  -> 现有本地路由 / 目录检索 / Prompt
  -> 现有同步 LLM HTTP                    无数据库事务
  -> TurnLifecycleService.completeTurn     短事务
  -> 兼容 ChatTurnResponse
```

`ChatService.sendMessage` 和承担模型调用的内部方法不得标注 `@Transactional`。会话创建、普通查询和独立清理操作可以保留各自短事务。

### 开始 Turn

`SendMessageRequest` 增加可选 `idempotencyKey`：

- 旧客户端未提供时，服务端为本次请求生成 UUID；这只保证兼容，不保证跨重试幂等。
- 新客户端提供时，服务端校验长度和格式，并交给 `beginTurn`。
- `Started` 继续执行聊天。
- `Replayed` 直接返回原结果，不调用模型。
- `InProgress` 返回 HTTP 409 和 `TURN_IN_PROGRESS`。
- 幂等键对应不同请求返回 HTTP 409 和 `IDEMPOTENCY_KEY_CONFLICT`。
- 成功 Turn 的 assistant 消息已被裁剪时返回 HTTP 409 和 `IDEMPOTENCY_EXPIRED`，不得重新调用模型。

### 上下文与生成

上下文只由 `summary_json` 和近期 SUCCEEDED 消息构成。继续复用当前 ChatService 内部的本地路由、目录服务、Prompt 构建和同步模型调用，不在本期创建新的框架层。

模型调用期间不得持有数据库事务或行锁。模型成功后只保存最终完整 assistant 消息；不保存中间片段。

### 完成与失败

模型成功后调用 `completeTurn`，在一个短事务中保存 assistant 消息、回放 metadata、摘要和 SUCCEEDED 终态。

任何路由、目录、Prompt 或模型异常都必须映射为稳定错误码，并调用 `failTurn(REQUIRES_NEW)`。客户端只能看到稳定错误信息，不能看到 provider 原始响应、URL、Authorization、异常堆栈或内部配置。

## REST 契约

请求保持向后兼容：

```json
{
  "content": "用户消息",
  "idempotencyKey": "可选字符串"
}
```

成功响应保留现有 message、catalogReport、catalogItems、intent、queryTarget、nextAction，并增加可选 `traceId`。不在本期增加复杂的流式 turn/error DTO。

错误使用真实 HTTP 状态：

| HTTP | 稳定错误 |
|---|---|
| 400 | `INVALID_REQUEST` |
| 404 | `SESSION_NOT_FOUND`、`AGENT_STREAMING_DISABLED` |
| 409 | `TURN_IN_PROGRESS`、`IDEMPOTENCY_KEY_CONFLICT`、`IDEMPOTENCY_EXPIRED` |
| 503 | `AI_PROVIDER_TIMEOUT`、`AI_PROVIDER_UNAVAILABLE` |
| 500 | `AGENT_INTERNAL_ERROR` |

错误 envelope 可包含 `traceId`，但不得包含内部异常文本。

## SSE 策略

服务端和 Flutter 的 SSE 开关固定为关闭。保留路由时，SSE 请求立即返回 HTTP 404 和 `AGENT_STREAMING_DISABLED`，不得提交异步任务或调用模型。

本期删除或绕过 `CompletableFuture.runAsync` 路径，不实现流式重试、断连取消和有界 executor。

## 会话与消息清理

所有清理必须通过一个授权后的 Agent 清理边界执行，不能由 ChatService 分别操作表。

- **清空消息：** 删除 session 下的 messages 和 turns，重置 `summary_json`、`summary_updated_at`、`next_sequence_no=1`，保留 session。
- **删除会话：** 先删除 messages 和 turns，再软删除 session。
- **清空某 persona 会话：** 对每个用户所有的活动 session 执行相同删除顺序。
- **删除单条消息：** 按 `turn_id` 删除整个 Turn 的 USER/ASSISTANT 消息和 Turn；不允许只删除一侧。旧的 idempotency 记录随用户明确删除而移除。

清理必须遵循 session -> messages -> turns -> session 的一致锁和删除语义，避免外键错误和部分删除。

## 诊断

删除以下虚假能力：

- `AgentTraceService`
- `AgentToolAuditEntity` 过渡类型
- `AgentToolAuditRepository` no-op
- `/api/agent/traces`

同时删除 `ChatService` 和 `AgentPlanService` 对旧 audit/trace repository 的写入与构造参数，避免保留看似成功但实际丢弃数据的调用。

使用一个小型结构化日志组件记录：

- traceId
- turnId
- operation
- terminalStatus
- durationMs
- stableErrorCode
- modelName（若已知）
- sessionId 的单向哈希

禁止记录消息正文、完整 Prompt、健康信息、邮箱、手机号、Token、Authorization、网关 URL、原始 provider 响应或异常堆栈内容。日志不写数据库，不承诺长期保留。

## Flutter 最小调整

- `AppShell` 是唯一按当前用户隔离的 Agent composition root。
- 删除或改造 `AssistantPage` 的重复网络、Token 和 controller 装配。
- 客户端 SSE 固定为 false，不再读取入口不一致的 `AI_STREAM_ENABLED`。
- controller 每次只加载最新 20 条消息，不继续向前分页。
- 页面移除“加载更早消息”入口。
- 保留会话列表、清空和删除能力。
- 不实现 SSE terminal DTO、重试队列或自动重放 POST。

## 测试设计

只新增一个后端真实闭环测试文件，例如 `AgentChatFlowIntegrationTest.kt`，使用：

- MySQL 8.0.39 Testcontainers；数据库名从 worktree 派生，迁移前打印 host/name。
- Spring Boot/MockMvc 或等价 Controller + Service 真实调用。
- 本地确定性假 LLM HTTP server；不得访问公网。

测试场景限定为：

1. 正常聊天返回兼容响应，并持久化一个 SUCCEEDED Turn、一个 USER 和一个 ASSISTANT。
2. 同 idempotency key/同请求稳定重放，模型只调用一次。
3. 同 key/不同请求返回 409。
4. provider 失败返回 503，Turn 为 FAILED 且没有 assistant 消息。
5. 会话不存在返回 404；SSE 返回 `AGENT_STREAMING_DISABLED`。
6. 清空与删除后没有孤立 messages/turns，session 重置或软删除符合语义。

继续运行现有 `AgentWorkflowCoreTest` 和 `AgentV2MySqlIntegrationTest`。不增加 Router、Tool、Prompt、LangChain 或 SSE 测试矩阵。

Flutter 只更新现有 Agent controller/transport/view 测试，验证同步 REST、最新 20 条和无“加载更早”入口；不新增大量 plumbing 测试文件。

## 未提交 Task 3 的处理

当前未提交的 ModelGateway、Router、Tool Registry、PromptAssembler 和目录查询重写先保存到独立本地分支 `codex/ai-agent-task3-spike` 的 WIP 提交，再让主分支恢复到已评审的 `9f14ff0`。该 WIP 分支不合并、不部署，只用于未来按实际需求选择性恢复。

## 完成标准

- 同步 REST 真实聊天闭环通过隔离 MySQL + 本地假 LLM 测试。
- 模型调用期间没有数据库事务。
- 每个成功请求只有一个 Turn、一个 USER 和一个 ASSISTANT。
- 幂等重放不重复调用模型。
- 错误使用真实 HTTP 状态且不泄露内部异常。
- 清理操作不产生外键错误或孤立记录。
- SSE 在服务端和 Flutter 均明确关闭。
- 无 traces API、audit no-op、LangChain4j、Tool Registry、PromptAssembler 或目录查询重写进入本期主分支。
