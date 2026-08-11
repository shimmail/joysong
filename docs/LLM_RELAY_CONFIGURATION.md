# JoySong 大模型中转站配置指南

`joysong-server` 通过 OpenAI Chat Completions 兼容的中转站调用模型。本版本只使用同步 REST 工作流，SSE 已禁用；不要配置 `stream: true`，也不需要修改 Android 以支持流式消息。

## 配置

```dotenv
AI_AGENT_ENABLED=true
OPENAI_API_KEY=替换为中转站密钥
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
AI_AGENT_MODEL=gpt-5.5
OPENAI_PROXY_URL=http://proxy.example.internal:8080
OPENAI_STREAM_ENABLED=false
OPENAI_INTENT_PARSER_ENABLED=true
OPENAI_INTENT_MODEL=可选的意图解析模型ID
```

服务端请求地址为：

```text
POST {OPENAI_BASE_URL}/chat/completions
Authorization: Bearer {OPENAI_API_KEY}
Content-Type: application/json
```

生产启用 Agent 时必须显式设置 `AI_AGENT_MODEL`。`OPENAI_INTENT_MODEL` 仅在 `OPENAI_INTENT_PARSER_ENABLED=true` 时供解析器使用；留空即使用 `AI_AGENT_MODEL`。两类调用共享 API Key、Base URL、代理、HTTP 客户端安全策略与 Provider 协议；解析器连接/读取超时固定为 3 秒/8 秒，最终生成保持 10 秒/60 秒，且最终回答始终调用 `AI_AGENT_MODEL`。`OPENAI_BASE_URL` 必须使用批准的 `https://www.fastaitoken.com` 地址，且不要配置成完整的 `/chat/completions` 地址，否则会重复拼接路径。可选的 `OPENAI_PROXY_URL` 只接受带主机和显式端口的 `http://` 或 `socks://` URL；运行时不支持并会拒绝 `https://` proxy URL。API Key 只存在于服务端 Secret 或环境变量，不能提交到 Git、写入 Android 工程或返回给客户端。

意图路由遵循固定顺序：当前请求的中英文否定感知关键词，受限的近期上下文补全，最后才是模型解析。当前请求中已明确的目标不受历史或模型结果覆盖；解析器失败时保留本地路由并继续最终生成。

应用未设置开关时默认 `AI_AGENT_ENABLED=true`。生产灰度部署必须故意显式覆盖为 `false`；完成 preflight、Flyway、readiness 和一次不输出 Key/Authorization/完整内容的 FastAIToken canary 后，才可在运维侧为内部 cohort 显式开启。当前应用不提供自动 cohort 分流或指标平台；具体步骤见 [`AI_AGENT_ROLLOUT.md`](./AI_AGENT_ROLLOUT.md)。

## 同步响应与错误处理

中转站调用完成后，后端在同一个 REST 响应中返回 Agent 结果。`summary_json` 是 `agent_sessions` 的服务器端持久摘要和上下文构建来源，不在当前 REST response 暴露。客户端按稳定 HTTP 状态处理：`2xx` 为成功，`400` 为无效输入，`404` 为资源不存在，`409` 为幂等冲突，`503` 为上游模型不可用或超时，`500` 为未预期服务端错误。请求体中的 `idempotencyKey` 是可选字段；相同 session 的重复有效键必须复用稳定结果。流式路由固定返回 `404` 和 `AGENT_STREAMING_DISABLED`。

## 日志与数据保护

中转调用使用结构化脱敏日志，只保留请求关联标识、模型标识、耗时、HTTP 状态与结果分类。绝不记录 API Key、Authorization、完整 prompt、完整回答或原始健康信息。本版本移除了 traces API；故障排查应使用受控服务端日志，不向客户端暴露模型调用轨迹。

## 不在本版本的能力

本版本不接入 LangChain4j、工具调用循环或自动记忆。会话摘要保存于 MySQL，模型上下文仅读取最近 20 条且不超过 7 天的消息；该窗口不等于自动记忆。

实现探索保存在未合并的 `codex/ai-agent-task3-spike` 分支，不能作为当前同步 REST 接口的运行时依赖。
