# JoySong AI Agent 开发文档

## 本次交付边界

本版本仅提供同步 REST Agent 工作流：客户端发起请求，服务端在同一个 HTTP 响应中返回结果。SSE 在本版本禁用，Android 不需要实现流式消息渲染或流式重连。

每次成功工作流以 MySQL 中的一条 `agent_turns` 记录及其消息为边界。`summary_json` 是 `agent_sessions` 中服务器端持久化的紧凑结构化摘要，供上下文构建使用；当前 REST response 不暴露该字段。它不是可执行指令，也不保存完整模型提示词或原始敏感内容。

本版本明确不包含：LangChain4j、模型工具调用循环、自动记忆、trace 读取 API 或 `agent_tool_audits`。不应为这些未交付能力新增客户端调用、数据库查询或运行时依赖。

## 意图路由与模型配置

路由先检查当前请求的中英文关键词，并识别否定表达；只有当前信息不足时，才使用受限的近期上下文补全。仍无法确定时才调用模型解析。当前请求中已明确的意图或目录目标优先于历史，解析结果不能覆盖它。

启用 `OPENAI_INTENT_PARSER_ENABLED=true` 时，`OPENAI_INTENT_MODEL` 可为解析器选择模型；空值回退到 `AI_AGENT_MODEL`。解析器与最终生成共享 `OPENAI_API_KEY`、`OPENAI_BASE_URL`、`OPENAI_PROXY_URL`、HTTP 客户端安全策略和 Provider 协议；解析器使用 3 秒连接/8 秒读取超时，最终生成使用 10 秒连接/60 秒读取超时。最终回答始终调用 `AI_AGENT_MODEL`；解析器超时、上游失败或返回无效内容时，工作流使用本地路由继续完成，不会把解析失败作为用户请求失败。

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

## REST 语义

同步接口使用稳定的 HTTP 状态：成功响应为 `2xx`，输入不合法为 `400`，找不到资源为 `404`，同一请求冲突为 `409`，上游模型不可用或超时为 `503`，未预期服务端错误为 `500`。调用方应按 HTTP 状态处理结果，不能依赖错误文本匹配。流式路由返回 `404` 和 `AGENT_STREAMING_DISABLED`，不会调用模型。

创建 Agent turn 时请求体可选传递 `idempotencyKey`。同一 session 中重复的有效键返回已创建的稳定结果，不应额外创建 turn 或消息；未传该键的调用仍受支持。

## 结构化卡片导航契约

结构化卡片可以使用自身的目录 `id` 以及 `doctorId`、`projectId`、`institutionId` 打开医生、项目或机构详情。这些 ID 表示业务资源，**不能**当作私聊对端用户 ID。

当前 Agent 目录响应没有显式的 `humanUserId`（或等价、经服务端授权的真人账号字段），因此客户端有意隐藏结构化卡片上的“真人咨询”入口。不得使用 doctor、project、institution 或 institution-project ID 猜测/替代聊天用户 ID。后续只有在服务端增加明确的真人用户字段、访问授权语义和对应契约测试后，客户端才能接入并显示该入口。

## 可观测性与隐私

服务端只记录结构化且脱敏的日志，例如请求关联标识、session/turn 标识、模型标识、耗时、HTTP 状态和结果分类。不得记录 Authorization、API Key、完整提示词、完整回答或原始健康信息。排障通过受控日志与数据库运维流程完成；本版本没有 traces API。

## 验证与实验分支

详细命令见 [`AI_AGENT_TESTING.md`](./AI_AGENT_TESTING.md)。其中 MySQL 集成测试必须使用当前 worktree 的隔离数据库，不能连接共享开发数据库。

应用运行时默认 `AI_AGENT_ENABLED=true`。生产灰度发布会故意显式覆盖为 `false`：先进入维护窗口并停止全部旧写实例，再在同一冻结窗口完成 V10 preflight、单实例 Flyway 和 readiness；迁移完成前不得恢复流量。随后完成 FastAIToken canary 和内部 cohort 观察后再显式启用。灰度、监控、立即停用和 V15 不可删除的操作步骤见 [`AI_AGENT_ROLLOUT.md`](./AI_AGENT_ROLLOUT.md)。自动 cohort 分流和指标聚合不由当前应用实现，需要网关、发布平台和运维监控平台提供。

实现探索保存在 `codex/ai-agent-task3-spike` 分支。该 spike 用于保留实验记录，未合并到本次同步 REST 交付。
