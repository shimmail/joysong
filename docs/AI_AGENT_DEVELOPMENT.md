# JoySong AI Agent 开发文档

## 本次交付边界

本版本仅提供同步 REST Agent 工作流：客户端发起请求，服务端在同一个 HTTP 响应中返回结果。SSE 在本版本禁用，Android 不需要实现流式消息渲染或流式重连。

每次成功工作流以 MySQL 中的一条 `agent_turns` 记录及其消息为边界。`summary_json` 是 `agent_sessions` 中服务器端持久化的紧凑结构化摘要，供上下文构建使用；当前 REST response 不暴露该字段。它不是可执行指令，也不保存完整模型提示词或原始敏感内容。

本版本明确不包含：LangChain4j、模型工具调用循环、自动记忆、trace 读取 API 或 `agent_tool_audits`。不应为这些未交付能力新增客户端调用、数据库查询或运行时依赖。

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

## 可观测性与隐私

服务端只记录结构化且脱敏的日志，例如请求关联标识、session/turn 标识、模型标识、耗时、HTTP 状态和结果分类。不得记录 Authorization、API Key、完整提示词、完整回答或原始健康信息。排障通过受控日志与数据库运维流程完成；本版本没有 traces API。

## 验证与实验分支

详细命令见 [`AI_AGENT_TESTING.md`](./AI_AGENT_TESTING.md)。其中 MySQL 集成测试必须使用当前 worktree 的隔离数据库，不能连接共享开发数据库。

实现探索保存在 `codex/ai-agent-task3-spike` 分支。该 spike 用于保留实验记录，未合并到本次同步 REST 交付。
