# JoySong 大模型中转站配置指南

`joysong-server` 通过 OpenAI Chat Completions 兼容的中转站调用模型。本版本只使用同步 REST 工作流，SSE 已禁用；不要配置 `stream: true`，也不需要修改 Android 以支持流式消息。

## 配置

```dotenv
AI_AGENT_PROVIDER=qwen
AI_AGENT_API_KEY=替换为模型服务密钥
AI_AGENT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_AGENT_MODEL=qwen-plus
AI_AGENT_INTENT_MODEL=qwen-turbo
```

服务端请求地址为：

```text
POST {AI_AGENT_BASE_URL}/chat/completions
Authorization: Bearer {AI_AGENT_API_KEY}
Content-Type: application/json
```

生产必须显式设置五项 Agent 变量，任何一项为空都会导致启动失败。`AI_AGENT_MODEL` 负责最终回答，`AI_AGENT_INTENT_MODEL` 负责意图分类，两个模型 ID 必须独立填写。两类调用共享 API Key、Base URL 与 Provider 协议；客户端固定直连，超时固定在代码中。Base URL 不要配置成完整的 `/chat/completions` 地址。API Key 只存在于服务端 Secret 或环境变量。

意图路由遵循固定顺序：当前请求的中英文否定感知关键词，受限的近期上下文补全，最后才是模型解析。当前请求中已明确的目标不受历史或模型结果覆盖；解析器失败时保留本地路由并继续最终生成。

应用固定启用 Agent。生产灰度与紧急停用由网关或发布平台控制入口；当前应用不提供自动 cohort 分流或指标平台。

## 同步响应与错误处理

中转站调用完成后，后端在同一个 REST 响应中返回 Agent 结果。`summary_json` 是 `agent_sessions` 的服务器端持久摘要和上下文构建来源，不在当前 REST response 暴露。客户端按稳定 HTTP 状态处理：`2xx` 为成功，`400` 为无效输入，`404` 为资源不存在，`409` 为幂等冲突，`503` 为上游模型不可用或超时，`500` 为未预期服务端错误。请求体中的 `idempotencyKey` 是可选字段；相同 session 的重复有效键必须复用稳定结果。流式路由固定返回 `404` 和 `AGENT_STREAMING_DISABLED`。

## 日志与数据保护

中转调用使用结构化脱敏日志，只保留请求关联标识、模型标识、耗时、HTTP 状态与结果分类。绝不记录 API Key、Authorization、完整 prompt、完整回答或原始健康信息。本版本移除了 traces API；故障排查应使用受控服务端日志，不向客户端暴露模型调用轨迹。

## 不在本版本的能力

本版本不接入 LangChain4j、工具调用循环或自动记忆。会话摘要保存于 MySQL，模型上下文仅读取最近 20 条且不超过 7 天的消息；该窗口不等于自动记忆。

实现探索保存在未合并的 `codex/ai-agent-task3-spike` 分支，不能作为当前同步 REST 接口的运行时依赖。
