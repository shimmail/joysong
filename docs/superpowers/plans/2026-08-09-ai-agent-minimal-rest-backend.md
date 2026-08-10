# AI Agent Minimal Synchronous REST Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing synchronous AI chat path deployable by connecting `ChatService` to the reviewed Turn lifecycle, using real HTTP errors, unified cleanup, and redacted logs without adding new Agent framework layers.

**Architecture:** `ChatService` remains the single application workflow and reuses its current router, catalog, prompt, and OpenAI-compatible HTTP code. It calls `TurnLifecycleService` for short begin/complete/fail transactions and `AgentContextBuilder` for bounded MySQL history; the external model call runs without a database transaction. SSE is explicitly disabled.

**Tech Stack:** Kotlin 1.9.22, Java 17, Spring Boot 3.2.2, Spring MVC, Spring Data JPA, Flyway, MySQL 8.0.39 Testcontainers, JUnit 5, MockMvc, JDK `HttpServer`, SLF4J.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor`.
- Preserve the reviewed V10 schema and Task 1–2 commits; do not add or modify a migration.
- Database changes and cleanup may affect only `agent_*` tables.
- Never connect tests or migrations to a shared development database.
- Derive the Testcontainers database name from the worktree; it must begin `myapp_worktree_`, and print host/database before Flyway.
- MySQL remains the only persistent chat-memory source.
- Do not add LangChain4j, LangGraph, Redis, vector storage, automatic memory, Tool Registry, PromptAssembler, a new Router, a new ModelGateway, or an Agent audit table.
- Keep SSE disabled; do not submit background stream work.
- Keep the REST request/response additive for old clients.
- Persist only final USER/ASSISTANT messages; never persist streaming fragments.
- Log no message body, prompt, health data, email, phone, token, Authorization header, gateway URL, raw provider response, or raw exception message.
- Add only one backend end-to-end test file: `AgentChatFlowIntegrationTest.kt`. Extend existing Task 1–2 tests only when an existing invariant changes.
- The known four `ProductionProfileTest` failures caused by ignored local configuration files are outside scope.

---

## File Structure

- `chat/service/ChatService.kt` — existing synchronous workflow; delegates persistence and terminal state to the lifecycle.
- `agent/orchestration/TurnLifecycleService.kt` — begin/complete/fail/cancel plus authorized conversation cleanup.
- `agent/context/AgentContextBuilder.kt` — bounded summary/recent history; no new framework responsibility.
- `agent/diagnostics/AgentOperationLogger.kt` — one small redacted structured logger.
- `agent/controller/AgentChatExceptionHandler.kt` — stable domain error to HTTP mapping.
- `chat/dto/ChatDtos.kt` — optional idempotency and trace fields.
- `chat/controller/ChatController.kt` — thin REST mapping; disabled SSE endpoint.
- `agent/AgentChatFlowIntegrationTest.kt` — the only new backend chat-flow test file.

---

### Task 0: Preserve the Deferred Task 3 Spike and Restore a Clean Main Branch

**Files:**
- Preserve on branch: current uncommitted backend Task 3 files under `joysong-server/src/main/kotlin/com/joysong/server/agent` and `joysong-server/src/test/kotlin/com/joysong/server/agent`
- Restore branch: `codex/ai-agent-architecture-refactor`

**Interfaces:**
- Consumes: current dirty worktree at design commit `01a50a5`.
- Produces: local branch `codex/ai-agent-task3-spike` containing one WIP commit; clean implementation branch at `01a50a5`.

- [ ] **Step 1: Verify the exact deferred files**

```powershell
git status --short
git diff --name-only
```

Expected: only the already audited Task 3 model/router/tool/prompt/catalog/plan/test changes are dirty. Stop if any unrelated file appears.

- [ ] **Step 2: Create the preservation branch**

```powershell
git switch -c codex/ai-agent-task3-spike
```

Expected: branch creation succeeds from `01a50a5`.

- [ ] **Step 3: Commit only the deferred spike**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent joysong-server/src/test/kotlin/com/joysong/server/agent
git diff --cached --check
git commit -m "wip: preserve deferred agent task3 spike"
```

Expected: the WIP commit contains no Flutter, non-Agent business, migration, or design-plan changes.

- [ ] **Step 4: Return to the implementation branch**

```powershell
git switch codex/ai-agent-architecture-refactor
git status --short
git rev-parse --short HEAD
```

Expected: clean worktree and HEAD `01a50a5`.

---

### Task 1: Connect ChatService to the Turn Lifecycle and Unify Cleanup

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/repository/ChatMessageRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/repository/ChatSessionRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentTurnRepository.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/AgentChatException.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: `TurnLifecycleService.beginTurn`, `completeTurn`, `failTurn`; `AgentContextBuilder.load`; current ChatService routing/catalog/prompt/LLM functions.
- Produces:

```kotlin
data class SendMessageRequest(
    val content: String,
    val idempotencyKey: String? = null
)

data class ChatTurnResult(
    val message: ChatMessageEntity,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val traceId: String? = null
)
```

Cleanup methods produced on `TurnLifecycleService`:

```kotlin
fun clearHistory(sessionId: String, userId: String)
fun deleteSession(sessionId: String, userId: String)
fun deleteTurn(messageId: String, userId: String)
fun clearSessions(userId: String, persona: String)
```

Stable domain error produced in this task:

```kotlin
class AgentChatException(
    val code: String,
    val traceId: String? = null
) : RuntimeException(code)
```

- [ ] **Step 1: Add the isolated real-chat integration test fixture**

Create `AgentChatFlowIntegrationTest.kt` with MySQL 8.0.39 Testcontainers and a companion JDK `HttpServer`. Derive the database name using the same worktree helper as `AgentV2MySqlIntegrationTest`; print:

```text
AGENT_CHAT_TEST_DB_HOST=<container host>
AGENT_CHAT_TEST_DB_NAME=myapp_worktree_ai_agent_architecture_refactor
```

Configure these dynamic properties before Spring starts:

```kotlin
registry.add("spring.datasource.url") { mysql.jdbcUrl }
registry.add("spring.datasource.username") { mysql.username }
registry.add("spring.datasource.password") { mysql.password }
registry.add("spring.flyway.enabled") { "true" }
registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
registry.add("openai.base-url") { "http://127.0.0.1:${fakeLlm.address.port}/v1" }
registry.add("openai.api-key") { "test-key" }
registry.add("openai.model") { "test-model" }
registry.add("openai.stream-enabled") { "false" }
registry.add("openai.intent-parser-enabled") { "false" }
registry.add("openai.demo-fallback-enabled") { "false" }
```

The fake server must respond to `/v1/chat/completions` with deterministic JSON and increment an `AtomicInteger` request count:

```json
{
  "id": "chatcmpl-test",
  "choices": [{"message": {"content": "测试回复"}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}
}
```

- [ ] **Step 2: Write the failing successful-turn and replay tests**

Use `chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))`, then:

```kotlin
val first = chatService.sendMessage(
    session.id,
    "user-1",
    SendMessageRequest("请介绍一下", "idem-success-1")
)
val replay = chatService.sendMessage(
    session.id,
    "user-1",
    SendMessageRequest("请介绍一下", "idem-success-1")
)

assertEquals("测试回复", first.message.content)
assertEquals(first.message.id, replay.message.id)
assertEquals(1, fakeLlmCalls.get())
assertEquals(1, turnRepository.countBySessionId(session.id))
assertEquals(listOf(1L, 2L), messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id).map { it.sequenceNo })
assertEquals(AgentTurnStatus.SUCCEEDED, turnRepository.findAll().single().status)
```

Autowire `@Qualifier("llmRestTemplate") RestTemplate` in the test and add a `ClientHttpRequestInterceptor` that records `TransactionSynchronizationManager.isActualTransactionActive()` in the calling thread. Assert the recorded value is `false`; remove the interceptor in `@AfterEach` so tests do not share state.

- [ ] **Step 3: Run the new test and verify RED**

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentChatFlowIntegrationTest'
```

Expected: FAIL because old ChatService writes USER/ASSISTANT with duplicate sequence zero and does not use Turn lifecycle/idempotency.

- [ ] **Step 4: Add optional idempotency and trace to the domain result**

Modify `SendMessageRequest` and `ChatTurnResult` with the signatures above. Normalize an absent key as a new UUID per request; reject provided keys that are blank after trim or longer than 100 characters.

Update lifecycle replay reconstruction so `ChatTurnResult.traceId` is the persisted Turn trace ID for both first completion and replay. Do not store traceId in message content.

Create `AgentChatException` with the signature above. Convert missing session, Turn-in-progress, key conflict, expired replay and stable provider failures into that type; Task 2 will assign HTTP statuses.

- [ ] **Step 5: Refactor synchronous ChatService persistence around lifecycle calls**

Remove `@Transactional` from `sendMessage` and the model-calling path. Use this control flow:

```kotlin
val key = request.idempotencyKey?.trim()?.also {
    require(it.isNotEmpty() && it.length <= 100) { "INVALID_IDEMPOTENCY_KEY" }
} ?: UUID.randomUUID().toString()

return when (val begin = turnLifecycleService.beginTurn(sessionId, userId, request.content, key)) {
    is BeginTurnResult.Replayed -> begin.turn
    BeginTurnResult.InProgress -> throw AgentChatException.turnInProgress()
    BeginTurnResult.IdempotencyExpired -> throw AgentChatException.idempotencyExpired()
    is BeginTurnResult.Started -> executeStartedTurn(begin, sessionId, userId, request.content)
}
```

Inside `executeStartedTurn`:

1. Load bounded context with `agentContextBuilder.load(userId, sessionId, 20, 4_000)`.
2. Reuse current route/catalog/prompt code and current synchronous `callLLM`.
3. Do not call `messageRepository.save` or `sessionRepository.save` directly.
4. Convert the final visible result into `CompleteTurnCommand` and call `completeTurn`.
5. On every exception after `Started`, call `failTurn(begin.turnId, stableCode, durationMs)` and rethrow a stable `AgentChatException`.

History sent to the model must come from `AgentContext.messages`; add the deterministic serialized summary as a system message only when it is not the empty schema.

- [ ] **Step 6: Write and implement deterministic cleanup tests**

In the same integration file, create successful turns and assert:

```kotlin
turnLifecycleService.clearHistory(session.id, "user-1")
assertEquals(0, messageRepository.countBySessionId(session.id))
assertEquals(0, turnRepository.countBySessionId(session.id))
assertEquals(1, sessionRepository.findById(session.id).orElseThrow().nextSequenceNo)
assertEquals("{}", sessionRepository.findById(session.id).orElseThrow().summaryJson)
```

Then test `deleteSession`, `clearSessions`, and `deleteTurn`:

- `deleteTurn` locates the user-owned message, obtains its non-null `turnId`, deletes both Turn messages first, then the Turn.
- `deleteSession` deletes messages, then turns, then soft-deletes the session.
- `clearSessions` performs the same operation only for the user's normalized persona.
- No operation may affect another user's session.

Implement all four cleanup methods as short transactions with a consistent session lock first. Make ChatService's existing delete/clear methods delegate to them.

- [ ] **Step 7: Limit history reads to recent successful messages**

Change `ChatService.getMessages` to return at most 20 messages from `AgentContextBuilder.load(userId, sessionId, minOf(limit, 20), 4_000)`. Ignore `before` for new clients but keep the parameter in the public method for binary/source compatibility until Flutter no longer sends it.

- [ ] **Step 8: Run lifecycle, integration, and migration tests**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest' --tests '*AgentChatFlowIntegrationTest'
git diff --check
```

Expected: PASS; fake LLM called once for replay; no RUNNING rows; no duplicate messages; cleanup has no FK error.

- [ ] **Step 9: Commit Task 1**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration joysong-server/src/main/kotlin/com/joysong/server/agent/context joysong-server/src/main/kotlin/com/joysong/server/agent/repository joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt
git commit -m "feat: connect chat to agent turn lifecycle"
```

---

### Task 2: Add Real HTTP Errors, Disable SSE, and Replace Fake Traces with Redacted Logs

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/controller/AgentChatExceptionHandler.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentOperationLogger.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/controller/AgentController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentEntities.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentRepositories.kt`
- Delete: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentTraceService.kt`
- Extend test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1 synchronous ChatService and `AgentChatException` stable codes.
- Produces: `ResponseEntity<BaseResponse<...>>` success/error responses, optional `ChatTurnResponse.traceId`, immediate disabled-SSE response, and structured redacted logs.

- [ ] **Step 1: Write failing MockMvc HTTP contract tests in the existing integration file**

Annotate the integration class with `@AutoConfigureMockMvc` and use `@WithMockUser(username = "user-1")` only if the configured authentication principal resolves to the username string; otherwise use the project's existing authentication test helper.

Add assertions:

```kotlin
mockMvc.perform(post("/api/chat/sessions/{id}/messages", session.id)
    .contentType(MediaType.APPLICATION_JSON)
    .content("""{"content":"请介绍一下","idempotencyKey":"http-1"}"""))
    .andExpect(status().isOk)
    .andExpect(jsonPath("$.data.message.content").value("测试回复"))
    .andExpect(jsonPath("$.data.traceId").isNotEmpty)
```

Also test:

- unknown session -> 404 / `SESSION_NOT_FOUND`;
- same key with different content -> 409 / `IDEMPOTENCY_KEY_CONFLICT`;
- existing running turn -> 409 / `TURN_IN_PROGRESS`;
- fake provider 5xx/timeout -> 503 with stable code and no provider body;
- `/messages/stream` -> 404 / `AGENT_STREAMING_DISABLED`, with fake LLM call count unchanged.
- cross-user get/delete/clear -> 404 / `SESSION_NOT_FOUND`, without revealing whether the session exists.

- [ ] **Step 2: Run HTTP tests and verify RED**

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentChatFlowIntegrationTest'
```

Expected: FAIL because ChatController still wraps errors in HTTP 200 and starts a common-pool SSE task.

- [ ] **Step 3: Define stable exceptions and ControllerAdvice**

`AgentChatExceptionHandler` maps `AgentChatException.code` to `HttpStatus` and returns:

```kotlin
data class AgentErrorData(val traceId: String? = null)

private fun statusFor(code: String): HttpStatus = when (code) {
    "INVALID_REQUEST", "INVALID_IDEMPOTENCY_KEY" -> HttpStatus.BAD_REQUEST
    "SESSION_NOT_FOUND", "AGENT_STREAMING_DISABLED" -> HttpStatus.NOT_FOUND
    "TURN_IN_PROGRESS", "IDEMPOTENCY_KEY_CONFLICT", "IDEMPOTENCY_EXPIRED" -> HttpStatus.CONFLICT
    "AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE
    else -> HttpStatus.INTERNAL_SERVER_ERROR
}

@ExceptionHandler(AgentChatException::class)
fun handle(error: AgentChatException): ResponseEntity<BaseResponse<AgentErrorData>> =
    statusFor(error.code).let { status ->
        ResponseEntity.status(status).body(
            BaseResponse(code = status.value(), message = error.code, data = AgentErrorData(error.traceId))
        )
    }
```

Map validation to 400, missing session to 404, in-progress/conflict/expired to 409, provider timeout/unavailable to 503, and unexpected Agent chat errors to 500 `AGENT_INTERNAL_ERROR`. Do not include `Throwable.message` unless it is one of the declared stable codes.

- [ ] **Step 4: Make ChatController thin and disable SSE synchronously**

Return `ResponseEntity` for send-message and disabled-stream endpoints. Remove every ChatController try/catch block that constructs JSON-only pseudo statuses, including get-messages, delete-session, clear-sessions, clear-messages and delete-message; let the scoped ControllerAdvice set the real status.

Use `authentication.name` instead of casting `authentication.principal as String`; this keeps production behavior and makes Spring Security test principals safe.

The SSE method must not create `SseEmitter` or call `CompletableFuture.runAsync`; it immediately throws/returns `AGENT_STREAMING_DISABLED` with HTTP 404. Remove `sendMessageStreaming` from ChatService if no production caller remains.

Extend `ChatTurnResponse`:

```kotlin
data class ChatTurnResponse(
    val message: ChatMessageResponse,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val traceId: String? = null
)
```

- [ ] **Step 5: Add the redacted operation logger tests and implementation**

In the same integration file, attach a test Logback appender to `AgentOperationLogger` and assert a success and provider-failure log contain traceId, operation, terminal status, duration and stable error code, while not containing request content, `test-key`, fake gateway URL, provider response, phone-like strings, or email-like strings.

Create a small component with methods:

```kotlin
fun completed(traceId: String, turnId: String, sessionId: String, durationMs: Long, modelName: String)
fun failed(traceId: String, turnId: String, sessionId: String, durationMs: Long, errorCode: String)
```

Log one key-value line. Hash session ID with SHA-256 and keep only the first 16 hex characters. Never pass a Throwable object to the logger.

Inject this component into ChatService. Call `completed` exactly once after `completeTurn` succeeds and `failed` exactly once after `failTurn` succeeds. Replayed requests may log operation `REPLAYED`, but must not emit a second model-completion event.

- [ ] **Step 6: Delete the fake trace API and no-op writers**

Delete `AgentTraceService`, remove `/api/agent/traces` and its injection, remove `AgentToolAuditEntity` and `AgentToolAuditRepository`, remove ChatService trace writes, and remove AgentPlanService audit construction/injection. Verify:

```powershell
Get-ChildItem -Recurse -File src/main/kotlin | Select-String -Pattern 'AgentTraceService|AgentToolAudit|/traces'
```

Expected: no production matches.

- [ ] **Step 7: Run the complete reduced backend verification**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest' --tests '*AgentCatalogServiceTest' --tests '*AgentProfileServiceTest' --tests '*AgentSafetyServiceTest' --tests 'com.joysong.server.config.OpenAiBaseUrlPolicyTest'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest' --tests '*AgentChatFlowIntegrationTest'
git diff --check
```

Expected: all targeted tests PASS; no SSE background thread; correct HTTP statuses; no audit no-op.

- [ ] **Step 8: Commit Task 2**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent joysong-server/src/main/kotlin/com/joysong/server/chat joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt
git commit -m "feat: stabilize synchronous agent REST errors"
```

---

### Task 3: Document and Verify the Reduced Backend Deliverable

**Files:**
- Modify: `docs/AI_AGENT_DEVELOPMENT.md`
- Modify: `docs/AI_AGENT_TESTING.md`
- Modify: `docs/LLM_RELAY_CONFIGURATION.md`

**Interfaces:**
- Consumes: completed synchronous REST contract.
- Produces: accurate development, testing, and relay documentation.

- [ ] **Step 1: Update operational documentation**

Document exactly:

- eight Agent tables;
- MySQL summary plus 20-message/7-day retention;
- synchronous REST only;
- optional idempotency key and stable HTTP statuses;
- SSE disabled in this release;
- structured redacted logs and removed traces API;
- no LangChain4j/tool loop/automatic memory;
- Task 3 spike preserved on `codex/ai-agent-task3-spike` but not merged.

Remove obsolete examples that query `agent_tool_audits` or claim Android code must implement streaming.

- [ ] **Step 2: Run fresh verification and isolation checks**

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest' --tests '*AgentCatalogServiceTest' --tests '*AgentProfileServiceTest' --tests '*AgentSafetyServiceTest' --tests 'com.joysong.server.config.OpenAiBaseUrlPolicyTest'
.\gradlew.bat mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest' --tests '*AgentChatFlowIntegrationTest'
git diff --check
git status --short
git diff --name-only 01a50a5...HEAD
```

Expected: targeted JVM and fresh Testcontainers suites PASS; only Agent/chat/docs paths from this plan changed; no migration or unrelated business module changes.

- [ ] **Step 3: Record the known full-suite baseline**

```powershell
.\gradlew.bat test --offline
```

Expected: either full PASS or only the four pre-existing `ProductionProfileTest` failures caused by absent ignored local config. Any additional failure blocks completion.

- [ ] **Step 4: Commit documentation**

```powershell
git add -- docs/AI_AGENT_DEVELOPMENT.md docs/AI_AGENT_TESTING.md docs/LLM_RELAY_CONFIGURATION.md
git commit -m "docs: document synchronous agent REST workflow"
```
