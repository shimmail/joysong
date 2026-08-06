# Joysong 大模型中转站配置指南

本文档说明如何让 `joysong-server` 通过 OpenAI 兼容中转站调用大模型。示例中转站：

```text
https://www.fastaitoken.com
```

> 安全提醒：中转站是第三方服务。医美对话可能包含健康、用药、过敏和外貌困扰等敏感信息。上线前应确认中转站的数据保留、日志、跨境传输和隐私条款；不要在客户端 App 中保存 API Key。

## 1. 项目当前的调用方式

后端使用 OpenAI Chat Completions 兼容格式：

```text
POST {OPENAI_BASE_URL}/chat/completions
Authorization: Bearer {OPENAI_API_KEY}
Content-Type: application/json
```

因此推荐配置：

```dotenv
OPENAI_API_KEY=替换为中转站密钥
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
OPENAI_MODEL=gpt-4.1-mini
```

最终请求地址将是：

```text
https://www.fastaitoken.com/v1/chat/completions
```

不要把 `OPENAI_BASE_URL` 配置成完整的 `/chat/completions` 地址，否则后端会重复拼接路径。

## 2. 先确认中转站支持的模型

FastAIToken 文档示例使用 `gpt-4.1-mini`。切换供应商或模型时，建议先使用中转站密钥查询模型列表：

```powershell
$headers = @{ Authorization = "Bearer $env:OPENAI_API_KEY" }
Invoke-RestMethod -Method Get `
  -Uri "https://www.fastaitoken.com/v1/models" `
  -Headers $headers
```

从返回结果的 `data[].id` 中选择一个支持 Chat Completions 的模型，并将完整 ID 写入 `OPENAI_MODEL`。如果 `/v1/models` 返回 404，应以中转站控制台提供的 Base URL 为准，确认它是否已经包含 `/v1`。

建议选择顺序：

1. 默认使用 FastAIToken 示例模型 `gpt-4.1-mini`。
2. 医美决策与风险说明优先保证推理质量，不再使用项目旧默认值 `gpt-3.5-turbo`。
3. 开发阶段可以选低成本模型；正式规划、风险解释和机构对比应单独进行质量评测。

## 3. 本地启动配置

### PowerShell 临时配置

这些变量只对当前终端窗口生效：

```powershell
$env:OPENAI_API_KEY="你的中转站密钥"
$env:OPENAI_BASE_URL="https://www.fastaitoken.com/v1"
$env:OPENAI_MODEL="从模型列表复制的模型ID"
./gradlew bootRun
```

### IntelliJ IDEA

打开后端启动配置，在 Environment variables 中添加：

```text
OPENAI_API_KEY=你的中转站密钥
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
OPENAI_MODEL=从模型列表复制的模型ID
```

不要把真实密钥写入 `application.yml`、`.env.example`、Android 工程或 Git。

## 4. 独立连通性测试

启动后端前，可以先直接测试中转站：

```powershell
$headers = @{
  Authorization = "Bearer $env:OPENAI_API_KEY"
  "Content-Type" = "application/json"
}
$body = @{
  model = $env:OPENAI_MODEL
  messages = @(
    @{ role = "system"; content = "Reply briefly and use the user's language." },
    @{ role = "user"; content = "Hello, reply with OK." }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post `
  -Uri "$env:OPENAI_BASE_URL/chat/completions" `
  -Headers $headers `
  -Body $body
```

成功响应应包含：

```text
choices[0].message.content
```

### 查询余额

```powershell
$headers = @{ Authorization = "Bearer $env:OPENAI_API_KEY" }
Invoke-RestMethod -Method Get `
  -Uri "https://www.fastaitoken.com/v1/usage" `
  -Headers $headers
```

不同网关版本可能返回 `remaining`、`quota.remaining` 或 `balance`，货币单位可能位于 `unit` 或 `quota.unit`。

### 流式输出

FastAIToken 使用 OpenAI 风格 SSE。请求体加入 `"stream": true` 后，服务端以增量内容返回，最终以 `data: [DONE]` 结束。当前 Joysong 后端使用非流式调用；若启用流式输出，需要同步改造后端接口与 Android 消息渲染，不能只修改请求参数。

## 5. 常见错误

| 状态或现象 | 常见原因 | 处理方法 |
|---|---|---|
| 后端返回“演示模式” | `OPENAI_API_KEY` 为空或启动进程未读取变量 | 检查启动配置并重启后端 |
| 401 | Key 错误、过期或 Authorization 格式不兼容 | 在中转站控制台重新生成 Key |
| 404 | Base URL 缺少或重复 `/v1`，或模型不存在 | 测试 `/v1/models`，复制准确模型 ID |
| 429 | 余额不足、并发或频率限制 | 检查余额、降低并发并增加退避重试 |
| 连接超时 | 中转站不可达、DNS/TLS 或服务器网络问题 | 在部署服务器上执行独立连通性测试 |
| 返回格式解析失败 | 中转站并非完全兼容 Chat Completions | 确认响应存在 `choices[0].message.content` |

FastAIToken 错误体采用 OpenAI 风格。常见业务错误码包括 `invalid_api_key`、`insufficient_quota`、`model_not_found`、`invalid_request_error`、`rate_limit_exceeded` 和 `server_error`。

## 6. 生产环境要求

- API Key 只放在服务器的 Secret/环境变量中，并定期轮换。
- 不记录完整请求正文、Authorization 请求头或用户敏感健康信息。
- 为中转站调用增加超时、限流、重试退避和熔断；不要对 4xx 错误盲目重试。
- 保存中转站请求 ID、模型 ID、耗时和结果状态，便于审计，但避免保存敏感原文。
- 中转站不可用时，应向用户明确提示服务暂不可用；医疗风险拦截不能因模型不可用而被绕过。
- 上线前用中英文测试集评估：风险识别、过度承诺识别、项目误配、机构比较和转人工触发。

## 7. 与当前 Agent 的关系

当前普通 AI 对话会调用该模型端点；结构化需求档案、安全筛查和基础规划仍由后端规则与数据库共同控制。中转模型不应直接绕过安全筛查、修改数据库或创建预约。后续接入工具调用时，预约和真人咨询必须继续经过后端权限校验与用户确认。

## 参考资料

- [OpenAI Models](https://developers.openai.com/api/docs/models)
- [OpenAI Chat Completions endpoint support](https://developers.openai.com/api/docs/models/chat-latest)
