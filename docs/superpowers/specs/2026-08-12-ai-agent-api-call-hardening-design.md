# AI Agent 接口调用收敛设计

## 目标

将当前工作区已有的 AI Agent 接口改动收敛到可测试、可交付状态，并补齐本轮最关键的接口可靠性缺口。范围限定为同步 REST 消息调用，不恢复 SSE，不引入新的 Agent 框架或 Provider 自动重试策略。

## 当前改动基础

现有未提交改动已覆盖三类能力：

- 后端兼容 GPT-5 Chat Completions 参数，按模型选择 `max_completion_tokens` 或 `max_tokens`。
- 后端记录脱敏后的 Provider 失败诊断，包括调用阶段、主机、模型、HTTP 状态、错误分类和耗时，不记录提示词或回复正文。
- Flutter 在用户手动重试时复用同一个消息幂等键，避免生成重复 turn。

这些改动将原样保留并通过测试收敛，不进行无关重构。

## 接口设计

Flutter 发送消息时，同一个幂等键同时写入请求体 `idempotencyKey` 和 HTTP 请求头 `Idempotency-Key`。请求使用项目现有的幂等 POST 能力，因此认证令牌过期时可以在刷新认证后安全地重放一次；请求体和请求头中的键在首次请求、认证刷新重放及用户手动重试期间保持一致。

后端继续以现有消息请求体契约处理幂等键。本轮不改变公开响应结构、状态码或数据库结构，也不新增迁移。

## Provider 调用与错误处理

后端保留同步 OpenAI Chat Completions 兼容调用：

- GPT-5 模型使用 `max_completion_tokens`，可按配置附带 `reasoning_effort`。
- 非 GPT-5 模型继续使用 `temperature` 和 `max_tokens`。
- Provider 失败继续映射为已有的 `AI_PROVIDER_TIMEOUT` 或 `AI_PROVIDER_UNAVAILABLE` 公共错误。
- 内部诊断细分认证、限流、模型不存在、非法请求、上游 5xx、连接超时、读取超时、网络错误和非法响应。
- 日志字段必须经过白名单或格式校验，不输出 API Key、Authorization、请求正文、健康信息或模型回复。

本轮不自动重试 429、5xx 或网络错误。Provider 自动重试会改变单次 turn 的时间预算与 lease 约束，应作为后续独立设计处理。

## 测试策略

按最小相关测试逐层验证：

1. 后端请求构造测试验证 GPT-5 与非 GPT-5 参数互斥及 reasoning effort 行为。
2. 后端 Agent 流程测试验证 Provider 失败诊断不会改变公共错误合同，并覆盖必要的错误分类。
3. Flutter 测试验证首次发送、失败后手动重试和认证刷新重放均复用稳定幂等键；请求头与请求体一致。
4. 运行相关后端测试类、Flutter Agent 相关测试及一次 `flutter analyze`。

不运行数据库迁移测试，因为本轮不修改迁移或持久化结构。相关测试通过后不重复运行同一命令；若出现环境型失败，记录证据而不无限重试。

## 变更边界

本轮不涉及：

- SSE、流式响应、取消协议或断线恢复。
- LangChain4j、工具循环、自动记忆或 traces API。
- Admin 管理页面或 Provider 配置界面。
- 连接池、熔断器、跨 Provider 灾备或自动退避重试。
- 当前工作区中的机构迁移、日记详情等无关改动。

## 验收标准

- 现有 AI Agent 相关未提交代码能够通过对应最小测试。
- GPT-5 与非 GPT-5 请求参数符合各自契约。
- Provider 失败日志可定位问题且不泄露敏感内容。
- Flutter 的消息幂等键在 body、header、认证刷新重放和用户手动重试中保持一致。
- 无新增迁移、无无关重构、无临时测试文件残留。
