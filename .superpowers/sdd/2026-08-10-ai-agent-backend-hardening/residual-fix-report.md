# AI Agent backend residual fix report

- 日期：2026-08-11
- 分支：`codex/ai-agent-production-hardening`
- 范围：仅统一 `AgentContextBuilder` 时间源，以及历史 `SUCCEEDED + PLANNING` 消息的读取边界安全投影。
- 未修改 V10 或其他迁移；未改写历史行；未访问共享数据库或公网。

## 1. AgentContextBuilder 统一 Clock

### RED

- 先将固定 UTC `Clock` 注入测试构造，旧生产构造器编译失败：`Clock` 被当作 `recentMessageLimit`，且参数数量超出。
- 新增精确边界行为测试：固定时间 `2026-08-10T04:00:00Z`，七天边界前 1ns 的消息应删除，恰好处于边界的消息应保留；返回消息必须按 `sequenceNo` 排序，摘要时间戳必须等于固定 Clock。

### 修复

- `AgentContextBuilder` 注入 Spring 单例 `Clock`，与 `TurnLifecycleService` 使用同一 Bean。
- `load`、`updateSummaryAndPrune` 的 retention cutoff，以及 `persistSummary` 的 `summaryUpdatedAt/updatedAt` 全部改为 `LocalDateTime.now(clock)`。
- 修正三个既有 retention 测试，使测试数据也基于固定 Clock，不再依赖运行机器本地时区。

## 2. 历史 PLANNING replay / GET 读取安全投影

### RED

- same-key replay 测试先返回历史自由文本 `Choose Project A because it is perfect for you`，并透传 downtime/risk/guaranteed-result metadata，安全正文断言失败。
- GET 所用 `AgentContextBuilder.load` 测试先返回原始历史 PLANNING 消息，固定安全正文断言失败。
- 自审确认 HTTP `GET /api/chat/sessions/{id}/messages` 不经过 `reconstruct`，因此补齐同一历史读取边界，而不是只保护 same-key replay。

### 修复

- 将新 PLANNING 已使用的固定信息参考正文集中到 `PlanningCatalogProjection.safeContent()`，避免生成链路与历史读取链路文案漂移。
- `PlanningCatalogProjection.projectStoredMessage` 仅识别 `ASSISTANT + intent=PLANNING`：
  - 返回 `ChatMessageEntity.copy`，不修改 JPA 历史实体；
  - 正文替换为固定信息参考文案；
  - `catalogItems/report` 复用现有安全投影；
  - metadata 只重建已知字段，丢弃旧自由文本及未知字段。
- `TurnLifecycleService.reconstruct` 和 `AgentContextBuilder.load` 复用同一投影。
- 非 PLANNING 返回原对象并保持既有正文、metadata、DTO 行为；原有 CATALOG_QA replay 测试继续覆盖该契约。
- 上下文 token budget 在安全投影后计算，避免固定安全正文使返回上下文超预算。

## TDD 与验证证据

- RED（Clock 构造契约）：`AgentWorkflowCoreTest` 编译失败，明确提示 `Clock` 类型不匹配/参数过多。
- RED（历史 replay）：单用例 1 test / 1 failure，固定安全正文断言失败。
- RED（历史 GET/load）：单用例 1 test / 1 failure，固定安全正文断言失败。
- GREEN：`test --offline --tests '*AgentWorkflowCoreTest'`，26/26 通过。
- GREEN：`mysqlIntegrationTest --offline --tests '*AgentChatFlowIntegrationTest.unsafe planning output*'`，1/1 通过，41 秒。
- 隔离 MySQL 证据：`AGENT_CHAT_TEST_DB_HOST=localhost`，`AGENT_CHAT_TEST_DB_NAME=myapp_worktree_ai_agent_production_hardening`（Testcontainers 临时 MySQL）。
- `git diff --check` 通过；仅有仓库既有 Gradle 9 deprecation/JVM class-sharing 警告。

## 自审与关注

- 未新增数据库迁移，不需要也不会改写历史数据；安全变换只存在于读取响应副本。
- metadata JSON 完全无法解析且没有可识别 `intent=PLANNING` 时保持既有容错行为，无法凭损坏 JSON 安全判断其意图；本次批准范围仅针对可识别的历史 `intent=PLANNING` 行。
- 若未来新增其他读取 Agent 消息的入口，应复用 `PlanningCatalogProjection.projectStoredMessage`，不能直接暴露历史实体。

## 复审补充：会话列表 lastMessage

- Important 复审发现：`GET /api/chat/sessions` 通过 `ChatService.getLastMessage` 直接读取最新 `ChatMessageEntity.content`，未经过 `reconstruct` 或 `AgentContextBuilder.load`，因此历史 `SUCCEEDED + PLANNING` 最新回复仍可能出现在会话列表。
- RED：新增真实 HTTP 会话列表 + 隔离 MySQL 回归测试，持久化旧 PLANNING 自由文本后，`lastMessage` 仍返回 `Choose Project A because it is perfect for you`；1 test / 1 failure。
- 修复：`getLastMessage` 对仓库返回值复用 `PlanningCatalogProjection.projectStoredMessage`，只读取投影副本的正文，不修改数据库行。
- GREEN：同一 Testcontainers 用例 1/1 通过，44 秒；同时确认数据库历史正文保持原样，普通 `CATALOG_QA` lastMessage 原样返回。
- 隔离数据库仍为 `myapp_worktree_ai_agent_production_hardening`；未运行全量测试，未扩大到其他路径。
