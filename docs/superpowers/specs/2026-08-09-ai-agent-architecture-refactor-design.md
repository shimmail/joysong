# AI Agent 架构与数据模型重构设计

## 1. 背景与目标

JoySong 当前 AI Agent 已支持多轮上下文、混合意图路由、平台目录检索、安全筛查、方案规划、结构化卡片和 OpenAI-compatible 模型调用。主要问题不是能力缺失，而是编排、事务、模型协议、持久化和观测集中在接近千行的 `ChatService` 中，导致职责耦合、外部调用跨数据库长事务、并发消息可能乱序、故障定位困难，并增加后续扩展成本。

本次重构目标是：

- 建立显式、可测试、可恢复的 Agent 工作流。
- 重构仅属于 AI Agent 的数据库表和字段。
- 保持现有客户端 REST/SSE 行为兼容。
- 保留确定性安全规则和平台数据库事实源。
- 将 LangChain4j 限制为可选模型适配层，不绑定业务编排。
- 在不影响其他业务并行开发的前提下渐进交付。

## 2. 范围与非目标

### 2.1 范围

- `agent_*` 会话、消息、档案、评估、安全、方案与运行观测数据。
- Agent Controller、工作流编排、模型网关、提示词、只读工具和追踪边界。
- Flutter Agent 依赖组装、配置、错误映射和最小关键流程。
- Agent 专用迁移、索引、精简测试和文档。

### 2.2 非目标

- 不引入开放式自主工具循环或多 Agent 系统。
- 不以 LangGraph 或独立 Python 服务重写现有后端。
- 不使用 LangChain4j 的独立内存存储；MySQL 仍是会话与业务事实源。允许通过自定义 `ChatMemoryStore` 使用 LangChain4j 管理受控的短期上下文窗口。
- 不修改机构、医生、项目、订单等非 Agent 业务表。
- 不为 Agent 到非 Agent 表增加数据库外键。
- 不建设完整 Prompt 自动评分平台、大规模 UI 自动化或高并发压测平台。

## 3. 技术决策

### 3.1 数据库

继续使用 MySQL 作为 Agent 事务数据和业务事实源。第一阶段使用精确 SQL、组合索引和必要的中文 ngram 全文索引消除 `findAll()` 后 JVM 过滤。未来若需要医学资料语义 RAG，可增加独立向量检索层，但召回结果必须回查 MySQL；不迁移主数据库。

现有 Agent 数据属于开发数据，允许在 Agent 域内一次性重新初始化。迁移不得删除或修改非 `agent_*` 表。

### 3.2 LangChain4j

LangChain4j 作为 `ModelGateway` 的可选实现，用于模型协议、结构化输出和流式适配；同时允许通过自定义 `ChatMemoryStore` 管理短期上下文窗口。默认先保留原生 OpenAI-compatible 实现，并通过同一组契约测试做兼容性试验。安全、鉴权、事务、工具许可、最大调用次数、超时和降级均由应用代码控制。

MySQL 是记忆的唯一持久化事实源。自定义 `ChatMemoryStore` 只读写 Agent 会话和消息，不建立第二套持久化状态；由应用代码执行用户与会话隔离、消息顺序、删除同步、敏感数据过滤以及 Token/消息数量上限。用户档案、安全评估、方案和目录事实不进入自动记忆，而由工作流按需加载为结构化上下文。

当前不引入 LangGraph。只有未来出现跨请求人工审批、长任务暂停恢复、多工具循环或断点续跑时，才单独评估图工作流运行时。

## 4. 目标架构

```text
ChatController
      |
      v
AgentTurnOrchestrator
      |
      +-- TurnLifecycleService
      +-- AgentContextBuilder
      |     `-- AgentChatHistoryPort
      |           `-- MySqlChatMemoryStore
      +-- AgentRouter
      +-- AgentToolRegistry
      |     +-- CatalogQueryTool
      |     +-- SafetyPolicyTool
      |     +-- PlanningEntryTool
      +-- PromptAssembler
      +-- ModelGateway
      |     +-- OpenAiCompatibleModelGateway
      |     +-- LangChain4jModelGateway (optional)
      +-- AgentDiagnostics
```

### 4.1 组件职责

- `AgentTurnOrchestrator`：只负责编排步骤和状态，不直接执行 SQL、HTTP 或维护大段提示词。
- `TurnLifecycleService`：创建 Turn、分配顺序号、幂等检查、状态迁移和恢复。
- `AgentContextBuilder`：读取短历史、当前详情上下文和已解析槽位。
- `AgentChatHistoryPort`：提供与框架无关的短期历史读取边界，使原生网关和 LangChain4j 网关使用同一上下文来源。
- `MySqlChatMemoryStore`：实现 `AgentChatHistoryPort` 和 LangChain4j `ChatMemoryStore` 适配，以 `agent_messages` 为唯一持久化来源，提供经过隔离、过滤和窗口裁剪的短期消息记忆。
- `AgentRouter`：执行本地确定性路由；仅在低置信度时调用结构化模型分类。
- `AgentToolRegistry`：注册受控白名单工具。首期工具均为只读或生成结构化入口，不允许模型任意调用业务写接口。
- `PromptAssembler`：按版本组装 persona、政策、数据库证据和响应约束。
- `ModelGateway`：屏蔽供应商协议、流式实现和结构化响应差异。
- `AgentDiagnostics`：输出不含对话正文的脱敏结构化日志；不建立独立运行审计表。

### 4.2 隔离边界

- 新代码位于 Agent/Chat 领域包内，不改变公共业务服务语义。
- 目录数据通过现有只读服务或 Agent 专用查询 Repository 获取。
- Agent 专用查询优化不得改变发现页等现有 API 行为。
- 客户端 REST/SSE 契约保持兼容；新增字段必须可选。

## 5. 数据模型

### 5.1 表分组

| 分组 | 表 | 职责 |
| --- | --- | --- |
| 会话执行 | `agent_sessions` | 用户会话、上下文、当前序号、滚动摘要、软删除 |
| 会话执行 | `agent_turns` | 一轮请求的顺序、幂等和执行状态 |
| 会话执行 | `agent_messages` | 有限保留的近期用户与助手消息及结构化响应 |
| 用户决策 | `agent_user_profiles` | 当前有效档案及确认版本 |
| 用户决策 | `agent_assessments` | 档案快照、规则版本、完整度和风险结论 |
| 用户决策 | `agent_safety_events` | 标准化风险事件和审核状态 |
| 方案 | `agent_plans` | 基于评估生成的不可变版本化方案 |
| 方案 | `agent_plan_items` | 方案阶段、项目引用快照与推荐依据 |

### 5.2 关系

```text
session 1 -- N turn 1 -- N message

profile 1 -- N assessment
assessment 1 -- N safety_event
assessment 1 -- N plan 1 -- N plan_item
```

### 5.3 关键约束

- `agent_turns(session_id, sequence_no)` 唯一。
- `agent_turns(session_id, idempotency_key)` 唯一。
- 同一会话最多存在一个 `RUNNING` Turn，由数据库约束配合应用锁或会话版本控制实现。
- Turn 状态限定为 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。
- `agent_turns` 保存最小诊断字段：`trace_id`、`error_code`、`fallback_used`、`model_name`、`prompt_version`、`started_at`、`completed_at` 和 `total_duration_ms`。
- `agent_messages` 包含 `turn_id`、`sequence_no`、`role`、`content_type` 和 `metadata_json`。
- 卡片和报告写入 `metadata_json`，在近期消息保留窗口内恢复结构化展示。
- `agent_sessions.summary_json` 保存滚动结构化摘要，至少包含目标、偏好、约束、已确认实体引用、未解决问题、`last_summarized_sequence` 和 `schema_version`；不得包含健康风险原文。
- 滚动摘要从已验证的路由槽位、用户明确表述和平台实体引用中确定性合并，不额外调用 LLM 生成自由文本摘要。
- 每个会话原始消息默认最多保留最近 20 条且最长保留 7 天，任一边界到达即可进入清理；保留数量与期限均可配置。
- JSON 字段使用 MySQL `JSON` 类型，并由数据库有效性与应用 DTO 双重校验。
- 金额使用 `DECIMAL` 并保存明确币种。
- `agent_plans(user_id, version)` 唯一，版本删除后不得复用。
- 风险事件使用稳定的 `risk_code` 和 `rule_version`，聊天路由与安全表单共享同一风险分类。
- 状态字段使用 `VARCHAR + CHECK`，Kotlin 使用 enum 映射。
- Agent 对项目、医生和机构只保存无外键引用及必要快照，避免与其他业务迁移耦合。

### 5.4 数据最小化与诊断

- 不建立 `agent_runs`、`agent_run_steps` 或新的数据库运行审计表；旧 `agent_tool_audits` 随 Agent V2 数据重建移除。
- 运行步骤仅输出脱敏结构化应用日志，包含 `trace_id`、步骤、终态、耗时、Token 数量和错误类别。
- 结构化日志不保存用户对话、健康信息、完整 Prompt、供应商密钥或原始异常体。
- 用户和会话标识在日志中使用哈希；授权的业务查询接口使用专用 DTO，不返回 JPA Entity。
- 失败生成内容、流式片段和图片临时文件不长期保存。
- 用户清空会话后立即从产品界面隐藏，并进入 Agent 数据清理流程；正式档案、评估和方案按各自业务生命周期管理。

### 5.5 索引

- 会话历史：`agent_messages(session_id, sequence_no)`。
- Turn 顺序：`agent_turns(session_id, sequence_no)`。
- 待处理执行：`agent_turns(status, created_at)`。
- 用户方案：`agent_plans(user_id, version)`。
- 风险审核：`agent_safety_events(user_id, review_status, created_at)`。

## 6. 工作流与事务

### 6.1 单轮流程

```text
1. 接收并校验请求
2. 短事务创建 Turn 和 USER 消息
3. 事务外构建上下文
4. 本地安全检测与意图路由
5. 必要时调用结构化模型分类
6. 执行白名单只读工具
7. 组装版本化提示词
8. 事务外调用回答模型
9. 短事务保存 ASSISTANT 消息并完成 Turn
10. 更新会话滚动摘要并输出脱敏结构化日志
```

### 6.2 状态与并发

```text
PENDING -> RUNNING -> SUCCEEDED
                   +-> FAILED
                   `-> CANCELLED
```

- 创建 Turn 时原子分配 `sequence_no`。
- 同一会话存在运行中 Turn 时，新请求等待受控时间或返回稳定的处理中响应，不交叉读取未完成历史。
- 新客户端传递幂等键；旧客户端未传递时服务端生成，保持接口兼容。
- 短期记忆只读取已完成 Turn 的消息，并按 Token 预算和最大消息数裁剪；不得把 `RUNNING`、`FAILED` 或 `CANCELLED` Turn 的部分内容加入模型上下文。
- 短期记忆由 `agent_sessions.summary_json` 与保留窗口内的近期消息共同组成；摘要更新失败不得覆盖上一版有效摘要。
- 最终 ASSISTANT 消息与 Turn 成功状态在同一个短事务提交。
- 模型成功但最终落库失败时，Turn 保持可恢复终态信息；恢复流程不得无条件重复调用模型。
- 流式 Token 不逐条写入 MySQL，完成后一次保存完整消息。

### 6.3 SSE

- 使用专用有界执行器，不使用 `CompletableFuture` 公共线程池。
- 客户端断开后取消流读取和后续处理。
- 终止事件统一包含 `traceId`、错误码和是否可重试。
- SSE 错误也必须把对应 Turn 更新为失败终态并输出结构化错误日志。

## 7. 错误处理与降级

- 仅对连接失败、超时、429 和允许重试的 5xx 执行有界重试，并受单轮总时间预算约束。
- 鉴权、参数、安全和结构校验错误不重试。
- 分类模型失败时回退本地路由；本地已识别安全意图不能被模型降级。
- 回答模型失败时保留已完成的数据库检索结果，并返回稳定的服务暂不可用状态。
- 不向用户展示网关 URL、供应商原始异常或代理配置。
- REST 返回正确 HTTP 状态；业务错误体和 SSE 错误事件共享稳定错误码。
- 主流程失败时仍必须在短事务中更新 Turn 终态和错误类别。

## 8. 版本与配置

每次运行记录：

- `workflowVersion`
- `routerVersion`
- `promptVersion`
- `safetyRuleVersion`
- 模型配置标识

Flutter 使用单一 composition root 和 typed `AgentConfig`。服务端模型开关与客户端 SSE 开关采用不同、明确的命名。移除 AppShell 与 AssistantPage 的重复依赖组装和相互冲突的默认值。

## 9. 精简测试策略

### 9.1 核心单元测试

只覆盖高风险规则：

- Turn 状态迁移与幂等。
- 同一会话顺序控制。
- MySQL 记忆的会话隔离、窗口裁剪和删除同步。
- 安全意图不可被模型降级。
- 路由失败时本地回退。
- 方案版本不可重复。
- 错误分类与重试判断。

### 9.2 服务端集成测试

使用独立工作树数据库和模拟 LLM 网关，验证：

- 全新空数据库可完成 Flyway 迁移。
- 完整聊天可创建 Turn、近期消息并更新会话滚动摘要。
- 模型成功、超时、429 和非法结构化响应。
- 并发提交不会产生乱序回复。
- 失败不会留下长事务或永久 `RUNNING` 状态。
- 原生网关与 LangChain4j 网关运行同一组精简协议用例。

执行迁移前必须打印解析后的数据库主机和数据库名。`WORKTREE_ID` 从当前 worktree 目录派生，数据库命名为 `myapp_<WORKTREE_ID>`，Docker Compose 项目名为 `myapp-<WORKTREE_ID>`。不得连接共享开发数据库，不得删除或重置名称不以 `myapp_worktree_` 开头的数据库。

### 9.3 最小回归测试

保留约六条 API 冒烟场景：

- 普通聊天。
- 数据库目录查询。
- 多轮上下文。
- 安全风险问题。
- 方案创建。
- SSE 成功或失败终止。

客户端仅保留 Controller/SSE 状态测试和一个关键页面流程测试。本次不建设完整 UI、双语、无障碍、Prompt 自动评分或压测矩阵。

不可省略的验收项：空数据库迁移、同会话并发与幂等、医疗安全不可降级。

## 10. 迁移策略

- 新增独立、明确命名的 Agent V2 Flyway 迁移。
- 只在 Agent V2 迁移中按依赖逆序删除旧 `agent_*` 表，再按正序创建新表。
- 不修改或删除已执行的 Flyway 历史迁移。
- 全新空数据库通过现有不可变迁移链依次执行到 Agent V2，不另行改写 V1 或 B1 基线。
- 实体、Repository、服务和 API DTO 与新结构在同一变更阶段更新，避免 Hibernate `validate` 启动失败。
- 迁移验证只在隔离的 worktree 数据库执行，并额外从全新空数据库验证完整 Flyway 链路。

## 11. 分阶段交付

1. 建立精简测试基线和 Agent V2 数据模型。
2. 拆分工作流组件，继续使用原生模型网关。
3. 启用短事务、Turn 幂等和会话顺序控制。
4. 统一服务端和客户端 trace/error contract，并以 Turn 最小诊断字段替代数据库运行审计。
5. 统一 Flutter 依赖组装和 Agent 配置。
6. 执行 LangChain4j 兼容性试验；只有全部精简协议用例通过后才通过配置灰度启用。

每个阶段必须保持可构建、可测试和可单独回退，不要求其他业务同步迁移。

## 12. 验收标准

- `ChatService` 不再承担事务、模型协议、Prompt、工具和追踪的全部职责。
- 外部 LLM 调用期间不存在数据库事务。
- 同一会话并发请求不会读取未完成历史或产生乱序回复。
- LangChain4j 短期记忆仅来自 MySQL 中已完成的本会话消息，且删除与窗口限制行为一致。
- 重复幂等请求不会重复调用模型或生成重复消息。
- Agent V2 迁移只操作 `agent_*` 表，并通过隔离空数据库验证。
- 失败执行均更新 Turn 最小诊断字段并输出脱敏结构化日志，且不会永久停留在 `RUNNING`。
- 安全规则仍由本地确定性逻辑控制，模型不能降低风险等级。
- 现有 REST/SSE 客户端主流程保持兼容。
- LangChain4j 不通过兼容性测试时，原生模型网关仍可独立完成所有验收场景。
