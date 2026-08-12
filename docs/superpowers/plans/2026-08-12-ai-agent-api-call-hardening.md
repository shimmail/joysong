# AI Agent API Call Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收敛现有 AI Agent 调用改动，并保证模型参数、Provider 故障诊断和 Flutter 消息幂等重放均有可验证契约。

**Architecture:** 保持现有同步 REST 与 `ChatService` 调用链，不新增 Provider 抽象或重试框架。后端通过纯请求构造函数和现有集成测试验证 GPT-5 兼容与脱敏诊断；Flutter 复用现有 `ApiClient.postIdempotent`，让同一幂等键同时进入 body 与 header，并在认证刷新及用户手动重试中保持稳定。

**Tech Stack:** Kotlin, Spring Boot, JUnit 5, MockK, Flutter/Dart, flutter_test

## Global Constraints

- 不恢复 SSE，不引入新的 Agent 框架或 Provider 自动重试策略。
- 不改变公开响应结构、状态码或数据库结构，不新增迁移。
- 不输出 API Key、Authorization、请求正文、健康信息或模型回复。
- 不修改机构迁移、日记详情等无关工作区改动。
- 先运行最小相关测试；同一条已通过命令不重复运行。

---

### Task 1: 收敛后端模型请求参数契约

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/chat/service/ChatCompletionRequestTest.kt`

**Interfaces:**
- Consumes: `buildChatCompletionRequest(model, messages, maxOutputTokens, temperature, reasoningEffort)`
- Produces: GPT-5 请求使用 `max_completion_tokens`，非 GPT-5 请求使用 `max_tokens` 与 `temperature`。

- [ ] **Step 1: 完成请求参数测试**

补齐断言：GPT-5 请求包含 `max_completion_tokens` 和非空 `reasoning_effort`，且不包含 `max_tokens`、`temperature`；非 GPT-5 请求执行相反断言；空 reasoning effort 不进入请求。

- [ ] **Step 2: 运行请求构造测试并确认当前结果**

Run: `./gradlew.bat test --tests com.joysong.server.chat.service.ChatCompletionRequestTest`

Expected: 若现有实现未完整满足契约则 FAIL；否则 PASS，随后只做代码审查和必要的最小修正。

- [ ] **Step 3: 最小化修正请求构造实现**

保持唯一构造入口：

```kotlin
internal fun buildChatCompletionRequest(
    model: String,
    messages: List<Map<String, String>>,
    maxOutputTokens: Int,
    temperature: Number,
    reasoningEffort: String?
): MutableMap<String, Any>
```

仅按 `model.trim().lowercase().startsWith("gpt-5")` 分支写入互斥参数。

- [ ] **Step 4: 验证后端请求测试通过**

Run: `./gradlew.bat test --tests com.joysong.server.chat.service.ChatCompletionRequestTest`

Expected: PASS。如果 Step 2 已 PASS，不重复执行。

### Task 2: 收敛 Provider 失败诊断与公共错误合同

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentOperationLogger.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: `AgentOperationLogger.providerFailed(...)`
- Produces: 结构化、脱敏的 `AGENT_PROVIDER` 日志，同时保持 timeout/unavailable 的既有 503 公共错误映射。

- [ ] **Step 1: 补齐 Provider 失败测试**

在现有流程测试中覆盖至少一个 HTTP Provider 失败和一个 timeout：断言 turn 最终进入 FAILED、公开异常码仍为 `AI_PROVIDER_UNAVAILABLE` 或 `AI_PROVIDER_TIMEOUT`，并验证诊断调用只接收主机、模型、状态、分类、耗时和统计值。

- [ ] **Step 2: 运行最小后端 Agent 流程测试**

Run: `./gradlew.bat test --tests com.joysong.server.agent.AgentChatFlowIntegrationTest`

Expected: 新断言在缺失分类或诊断行为时 FAIL。

- [ ] **Step 3: 最小化修正错误分类和脱敏**

保留以下内部分类集合：`AUTH`, `RATE_LIMIT`, `MODEL_NOT_FOUND`, `INVALID_REQUEST`, `UPSTREAM_5XX`, `CONNECT_TIMEOUT`, `READ_TIMEOUT`, `NETWORK`, `INVALID_RESPONSE`, `UNKNOWN`。Provider error code 必须匹配安全格式且不得含 `authorization|bearer|token|api-key|test-key` 等敏感标记。

- [ ] **Step 4: 验证后端 Agent 流程测试通过**

Run: `./gradlew.bat test --tests com.joysong.server.agent.AgentChatFlowIntegrationTest`

Expected: PASS。如果 Step 2 已 PASS，不重复执行。

### Task 3: 加固 Flutter 消息幂等请求

**Files:**
- Modify: `joysong-flutter/lib/features/agent/data/agent_remote_data_source.dart`
- Modify: `joysong-flutter/lib/features/agent/data/agent_repository_impl.dart`
- Modify: `joysong-flutter/lib/features/agent/domain/agent_repository.dart`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_busy_actions_test.dart`
- Test: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`

**Interfaces:**
- Consumes: `ApiClient.postIdempotent<T>(path, idempotencyKey:, body:, decodeData:)`
- Produces: `sendMessage(sessionId, content, {required idempotencyKey})` 在 header 和 body 中使用同一个键。

- [ ] **Step 1: 扩展 Flutter 失败重试契约测试**

断言控制器首次发送生成一个键，失败后用户手动重试复用该键；认证刷新重放由 `postIdempotent` 保持相同 `Idempotency-Key`，body 的 `idempotencyKey` 与之相同。

- [ ] **Step 2: 运行最小 Flutter 控制器测试**

Run: `flutter test test/features/agent/agent_chat_controller_test.dart`

Expected: 普通 `post` 未写入 header 时 FAIL，或现有 controller 层稳定键断言 PASS。

- [ ] **Step 3: 改用幂等 POST**

在 `ApiAgentRemoteDataSource.sendMessage` 中调用：

```dart
_apiClient.postIdempotent<ChatTurn>(
  'chat/sessions/$sessionId/messages',
  idempotencyKey: idempotencyKey,
  body: {
    'content': _validateContent(content),
    'idempotencyKey': idempotencyKey,
  },
  decodeData: ChatTurn.fromJson,
)
```

不得在 remote data source 内重新生成键。

- [ ] **Step 4: 运行 Flutter Agent 相关测试**

Run: `flutter test test/features/agent/agent_chat_controller_test.dart test/features/agent/agent_chat_busy_actions_test.dart test/features/agent/agent_chat_page_test.dart`

Expected: PASS。若 Step 2 已包含其中某个通过命令，不单独重复该命令。

### Task 4: 最终验证与工作区边界检查

**Files:**
- Verify only: all files listed above

**Interfaces:**
- Consumes: Tasks 1-3 的通过结果
- Produces: 可交付的窄范围改动和清晰的未解决风险说明。

- [ ] **Step 1: 运行一次 Flutter 静态分析**

Run: `flutter analyze`

Expected: No issues found，或只报告有证据的既有环境/无关问题。

- [ ] **Step 2: 检查差异质量和范围**

Run: `git diff --check`

Expected: 无 whitespace error。

Run: `git status --short`

Expected: AI Agent 文件保持在本计划范围内；机构迁移、日记详情等无关改动仍存在但未被改写、暂存或提交。

- [ ] **Step 3: 汇总验证证据**

报告每条测试命令、通过/失败结果、未运行全量测试的原因，以及后续独立候选：Provider 预算内重试、连接池与 Micrometer 指标。不得声称未执行的测试已通过。
