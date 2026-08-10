# AI Agent LangChain4j Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether LangChain4j can safely replace only the native model protocol and short-memory adapter without taking ownership of JoySong's workflow, safety, persistence, or tools.

**Architecture:** Implement a second `ModelGateway` behind a configuration switch and adapt the existing MySQL `AgentChatHistoryPort` to LangChain4j `ChatMemoryStore`. The native gateway remains the default and rollback path. Adoption stops if dependency compatibility, third-party gateway behavior, streaming cancellation, or memory semantics cannot pass the same contract tests.

**Tech Stack:** Java 17, Kotlin 1.9.22, Spring Boot 3.2.2, LangChain4j 1.18.1 low-level modules, JDK HTTP client, JUnit 5, MySQL Agent V2 schema.

## Global Constraints

- Complete `2026-08-09-ai-agent-v2-backend-core.md` first; this plan consumes its stable `ModelGateway`, `AgentChatHistoryPort`, Turn lifecycle, and REST/SSE contract.
- Work only in `D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor` on branch `codex/ai-agent-architecture-refactor`.
- Do not use LangChain4j AI Services, Agents, automatic tool loops, automatic RAG, or a second persistent memory store.
- MySQL remains the sole persistent memory source. LangChain4j must read only completed messages and the deterministic `agent_sessions.summary_json` context.
- Do not introduce `langchain_memory`, `agent_runs`, `agent_run_steps`, Redis, or a vector database.
- Do not use the LangChain4j Spring Boot starter because this project is on Spring Boot 3.2.2; create low-level clients explicitly.
- Use pinned dependencies `dev.langchain4j:langchain4j:1.18.1` and `dev.langchain4j:langchain4j-open-ai:1.18.1` only for the spike.
- Keep `AI_MODEL_GATEWAY=native` as the default in every environment.
- Never send safety screening, authorization, transaction, or tool-permission decisions to framework memory.
- Third-party OpenAI-compatible hosts require an explicit production allowlist; never permit arbitrary HTTPS hosts.
- If any exit criterion fails, document the result and keep the native gateway; do not force the adoption.

---

## File Structure

- Modify `joysong-server/build.gradle.kts` only for the two low-level modules.
- Create `agent/model/LangChain4jModelGateway.kt` for protocol adaptation.
- Create `agent/model/ModelGatewayConfiguration.kt` for native/LangChain selection.
- Create `agent/model/AgentIntentClassifier.kt` for typed classification with safe fallback.
- Create `agent/memory/MySqlChatMemoryStore.kt` as an adapter over the backend plan's `AgentChatHistoryPort`.
- Keep all business orchestration in `AgentTurnOrchestrator` unchanged.

---

### Task 1: Prove Dependency and Application-Context Compatibility

**Files:**
- Modify: `joysong-server/build.gradle.kts`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGatewayConfiguration.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/ConfigValidator.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicy.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayContractTest.kt`
- Modify Test: `joysong-server/src/test/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicyTest.kt`

**Interfaces:**
- Produces: `ModelGatewayConfiguration` that selects exactly one gateway by `AI_MODEL_GATEWAY`.
- Consumes: backend core `ModelGateway` and native implementation.

- [ ] **Step 1: Write the failing context smoke test**

Create two `ApplicationContextRunner` cases:

```kotlin
@Test
fun `native remains the default gateway`() { /* assert NativeOpenAiCompatibleModelGateway */ }

@Test
fun `langchain4j gateway can be selected without replacing workflow beans`() { /* assert one ModelGateway */ }
```

Also assert no AI Services/Agent bean is present.

- [ ] **Step 2: Add only the low-level dependencies**

```kotlin
implementation("dev.langchain4j:langchain4j:1.18.1")
implementation("dev.langchain4j:langchain4j-open-ai:1.18.1")
```

Do not add a BOM or Spring Boot starter, and do not override Jackson versions.

- [ ] **Step 3: Add explicit gateway configuration**

Add:

```yaml
openai:
  gateway-implementation: ${AI_MODEL_GATEWAY:native}
  structured-output-mode: ${OPENAI_STRUCTURED_OUTPUT_MODE:PROMPT_JSON}
  max-retries: ${OPENAI_MAX_RETRIES:0}
  allowed-hosts: ${OPENAI_ALLOWED_HOSTS:api.openai.com}
```

Validate `gateway-implementation` against `native/langchain4j` and `structured-output-mode` against `PROMPT_JSON/JSON_SCHEMA`. Parse the base URL host and require membership in the explicit production allowlist.

- [ ] **Step 4: Inspect dependency compatibility**

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat dependencyInsight --offline --dependency jackson-databind --configuration runtimeClasspath
.\gradlew.bat dependencyInsight --offline --dependency slf4j-api --configuration runtimeClasspath
```

Expected: one resolved Jackson and SLF4J line compatible with Boot 3.2.2. Any `NoSuchMethodError`, multiple incompatible major versions, or forced Jackson upgrade is a stop condition; revert dependencies and record “not adopted.”

- [ ] **Step 5: Run context and policy tests**

```powershell
.\gradlew.bat test --offline --tests '*ModelGatewayContractTest' --tests '*OpenAiBaseUrlPolicyTest'
```

Expected: PASS with native default and one selected gateway.

- [ ] **Step 6: Commit the reversible dependency boundary**

```powershell
git add joysong-server/build.gradle.kts joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGatewayConfiguration.kt joysong-server/src/main/kotlin/com/joysong/server/config joysong-server/src/main/resources/application.yml joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayContractTest.kt joysong-server/src/test/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicyTest.kt
git commit -m "build: add optional LangChain4j gateway"
```

---

### Task 2: Implement the LangChain4j Model Gateway Against the Native Contract

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/LangChain4jModelGateway.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/AgentIntentClassifier.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayContractTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGatewayConfiguration.kt`

**Interfaces:**
- Produces: a drop-in `ModelGateway`, typed intent classification, and a cancellable stream handle.
- Consumes: backend core `ModelRequest`, `StructuredModelRequest`, observer, response, and domain router validation.

- [ ] **Step 1: Parameterize the existing native gateway tests**

Create one contract that is run for `native` and `langchain4j` factories against the same in-process HTTP server. Required cases:

```text
non-streaming content and usage
/v1/chat/completions path and Bearer header
timeout, 401, 429, 500 mapping
stream delta order and [DONE]
stream provider error and client cancellation
exactly one terminal callback
```

- [ ] **Step 2: Run the contract and verify LangChain4j failure**

```powershell
.\gradlew.bat test --offline --tests '*ModelGatewayContractTest'
```

Expected: native cases PASS; LangChain4j cases FAIL because the adapter is absent.

- [ ] **Step 3: Implement low-level client mapping**

Construct `OpenAiChatModel` and `OpenAiStreamingChatModel` explicitly. Map only common Chat Completions fields. Do not send `reasoning_effort`, `response_format`, or `max_completion_tokens` until the configured capability mode permits it. Carry `traceId` explicitly because MDC does not automatically cross streaming callbacks.

Return the framework response through the existing domain type:

```kotlin
ModelResponse(
    content = response.aiMessage().text(),
    inputTokens = response.tokenUsage()?.inputTokenCount(),
    outputTokens = response.tokenUsage()?.outputTokenCount(),
    providerRequestId = extractedRequestId,
    finishReason = response.finishReason()?.name
)
```

- [ ] **Step 4: Add structured classification with explicit modes**

`PROMPT_JSON` asks for bounded JSON and validates it with Jackson. `JSON_SCHEMA` uses schema response format only when enabled. Both must validate legal enum values, at most eight keywords, and safety monotonicity. Illegal/timeout results return the local decision; they never throw a 500 to the user.

- [ ] **Step 5: Run gateway and classifier contracts**

```powershell
.\gradlew.bat test --offline --tests '*ModelGatewayContractTest'
```

Expected: both gateways pass common behavior; classifier passes valid JSON, invalid JSON, illegal enum, timeout fallback, and safety-cannot-downgrade.

- [ ] **Step 6: Commit the adapter**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent/model joysong-server/src/test/kotlin/com/joysong/server/agent/model
git commit -m "feat: implement LangChain4j model adapter"
```

---

### Task 3: Adapt MySQL Bounded History to ChatMemoryStore

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/memory/MySqlChatMemoryStore.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/memory/MySqlChatMemoryStoreMySqlIntegrationTest.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGatewayConfiguration.kt`

**Interfaces:**
- Produces: LangChain4j `ChatMemoryStore` backed by the existing `AgentChatHistoryPort` and V2 message repository.
- Consumes: backend core session ownership, succeeded-turn filter, deterministic summary, stable message IDs, and retention service.

- [ ] **Step 1: Write failing MySQL memory tests**

Test:

- `memoryId` includes both user and session identifiers.
- another user cannot read or clear the session.
- only `SUCCEEDED` turns are returned in `sequence_no` order.
- summary context and recent messages respect message and token limits.
- repeated `updateMessages()` is idempotent by stable ID/sequence, not content.
- LangChain window eviction does not physically delete UI-retained messages.
- `deleteMessages()` invokes the existing authorized clear flow.

- [ ] **Step 2: Run and verify the adapter is missing**

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --tests '*MySqlChatMemoryStoreMySqlIntegrationTest'
```

Expected: FAIL because the adapter does not exist.

- [ ] **Step 3: Implement the adapter without a second source**

Use a structured memory ID:

```kotlin
data class AgentMemoryId(val userId: String, val sessionId: String) {
    override fun toString(): String = "$userId:$sessionId"
}
```

`getMessages()` delegates to `AgentChatHistoryPort`. `updateMessages()` reconciles only stable persisted messages and never treats LangChain window eviction as a request to delete retained UI history. `deleteMessages()` calls the authorized clear operation. Do not store a serialized LangChain message list in `summary_json`.

- [ ] **Step 4: Run memory and backend retention tests together**

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --tests '*MySqlChatMemoryStoreMySqlIntegrationTest' --tests '*AgentV2MySqlIntegrationTest'
```

Expected: PASS with no ninth Agent table.

- [ ] **Step 5: Commit the memory adapter**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent/memory joysong-server/src/test/kotlin/com/joysong/server/agent/memory
git commit -m "feat: adapt MySQL agent history to LangChain4j"
```

---

### Task 4: Run Optional Live Compatibility and Decide Adoption

**Files:**
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayLiveCompatibilityTest.kt`
- Modify: `docs/LLM_RELAY_CONFIGURATION.md`
- Modify: `docs/AI_AGENT_TESTING.md`

**Interfaces:**
- Produces: an explicit adopt/reject decision and documented rollback.
- Consumes: completed Tasks 1–3 and an explicitly provided test gateway credential.

- [ ] **Step 1: Add an opt-in live test tag**

Tag the test `llm-live` and skip unless `OPENAI_API_KEY`, `OPENAI_BASE_URL`, and `AI_AGENT_MODEL` are all present. Never print their values. Test one non-streaming response, one structured classification in configured mode, and one cancellable stream.

- [ ] **Step 2: Run all offline contracts first**

```powershell
.\gradlew.bat test --offline --tests '*ModelGatewayContractTest'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*MySqlChatMemoryStoreMySqlIntegrationTest'
```

Expected: PASS before any live call.

- [ ] **Step 3: Run the live test only when credentials are deliberately supplied**

```powershell
.\gradlew.bat test --tests '*ModelGatewayLiveCompatibilityTest'
```

Expected: target gateway proves the exact enabled capabilities. An unsupported structured or streaming capability remains disabled; do not hide it with a fallback retry.

- [ ] **Step 4: Apply the adoption gate**

Adopt for staged use only if all are true:

```text
Boot 3.2.2 starts without binary conflicts
native and LangChain4j pass the same gateway contract
target gateway passes enabled live capabilities
MySQL memory isolation/ordering/retention passes
no database transaction is active during model calls
stream cancellation yields one terminal state
local safety remains authoritative
production host allowlist and redaction pass
AI_MODEL_GATEWAY=native remains the default rollback
```

If any item fails, keep the native gateway and document `LangChain4j evaluated but not adopted` with the failing capability.

- [ ] **Step 5: Update docs and commit the decision**

```powershell
git add docs/LLM_RELAY_CONFIGURATION.md docs/AI_AGENT_TESTING.md joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayLiveCompatibilityTest.kt
git commit -m "docs: record LangChain4j compatibility decision"
```
