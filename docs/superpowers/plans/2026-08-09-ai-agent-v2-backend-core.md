# AI Agent V2 Backend Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the isolated `agent_*` schema and replace the monolithic chat transaction with an explicit, idempotent, short-transaction Agent workflow while preserving existing REST/SSE clients.

**Architecture:** MySQL remains the sole persistent source for sessions, turns, bounded recent messages, structured summaries, profiles, assessments, safety events, and plans. `ChatService` becomes a compatibility façade over `AgentTurnOrchestrator`; routing, context, tools, prompt assembly, the native model gateway, and diagnostics are separate application components. No database run/audit tables are introduced.

**Tech Stack:** Kotlin 1.9.22, Java 17, Spring Boot 3.2.2, Spring Data JPA, Flyway, MySQL 8.0.39 Testcontainers, JUnit 5, MockK, RestTemplate, Flutter-compatible REST/SSE.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor` on branch `codex/ai-agent-architecture-refactor`.
- Database changes may touch only `agent_*` tables; do not alter non-Agent tables or add foreign keys to users, projects, doctors, institutions, or orders.
- Do not modify the immutable `V1__init_schema.sql` or `B1__init_schema.sql`; add `V10__rebuild_agent_v2.sql`.
- Derive `WORKTREE_ID` from the current worktree directory; use database `myapp_<WORKTREE_ID>` and Docker project `myapp-<WORKTREE_ID>`.
- Before migrations, print the resolved database host and database name. Never connect tests or migrations to a shared development database.
- Never drop or reset a database unless its name begins with `myapp_worktree_`; the approved V10 migration may rebuild only the listed `agent_*` tables.
- MySQL is the only persistent chat-memory source. Do not add Redis, a vector database, LangChain4j, or another memory table in this plan.
- Persist at most 20 recent messages per session and no message older than 7 days by default; both values must be configurable.
- `agent_sessions.summary_json` is a deterministic structured summary. Do not call an LLM to summarize it and do not store raw safety/health text in it.
- Do not create `agent_runs`, `agent_run_steps`, or another database audit replacement. Store only minimum diagnostic fields on `agent_turns` and emit redacted structured logs.
- The REST/SSE changes must be additive: old clients that send only `content` and parse existing fields must continue to work.
- Baseline note: targeted Agent backend tests pass. The full suite has four existing `ProductionProfileTest` failures because ignored local `application-dev.yml` and `application-prod.yml` files are absent in a clean worktree; do not copy local configuration or broaden this feature to fix that unrelated issue.

---

## File Structure

Create focused files:

- `joysong-server/src/main/resources/db/migration/V10__rebuild_agent_v2.sql` — one-time Agent-only schema rebuild.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentTurnEntity.kt` — Turn persistence and enums.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentTurnRepository.kt` — Turn locking/idempotency queries.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt` — short transactional state changes.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/AgentTurnOrchestrator.kt` — transaction-free workflow.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt` — MySQL history port, deterministic summary, and retention.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/router/AgentRouter.kt` — existing local routing plus bounded classification.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/tool/AgentToolRegistry.kt` — explicit read-only tools.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/prompt/PromptAssembler.kt` — versioned prompt assembly.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGateway.kt` — provider-neutral model contract.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/model/NativeOpenAiCompatibleModelGateway.kt` — extracted current protocol.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentDiagnostics.kt` — redacted structured logs.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentDebugRecorder.kt` — dev/test-only 24-hour diagnostic bundles.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/config/AgentProperties.kt` — typed configuration.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/config/AgentExecutionConfig.kt` — bounded SSE executor.
- `joysong-server/src/main/kotlin/com/joysong/server/agent/controller/AgentExceptionHandler.kt` — HTTP/SSE error mapping.

Modify existing files only where their current responsibility remains: chat entities/repositories/DTO/controller/façade, Agent entities/services, configuration, scripts, docs, and `.gitignore`.

---

### Task 1: Rebuild the Agent V2 Schema and Persistence Model

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V10__rebuild_agent_v2.sql`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentTurnEntity.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentTurnRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/entity/ChatSessionEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/entity/ChatMessageEntity.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/repository/ChatSessionRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/repository/ChatMessageRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentEntities.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentRepositories.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt`

**Interfaces:**
- Produces: `AgentTurnEntity`, `AgentTurnStatus`, `AgentTurnRepository`, session row locking, succeeded-message queries, and eight V2 tables used by every later task.
- Consumes: existing string UUID identifiers and Flyway V1/B1 through V9.

- [ ] **Step 1: Write the failing MySQL migration test**

Create a `@Tag("mysql-integration")` Testcontainers test following `AdminIdentityServiceMySqlIntegrationTest`. Assert Flyway versions `1..10`, exactly these Agent tables, and no `agent_tool_audits`, `agent_runs`, or `agent_run_steps`:

```kotlin
@Test
fun `empty database migrates to isolated agent v2 schema`() {
    val tables = jdbcTemplate.queryForList(
        """
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = DATABASE() AND table_name LIKE 'agent_%'
        """.trimIndent(),
        String::class.java
    ).toSet()

    assertEquals(
        setOf(
            "agent_sessions", "agent_turns", "agent_messages",
            "agent_user_profiles", "agent_assessments", "agent_safety_events",
            "agent_plans", "agent_plan_items"
        ),
        tables
    )
}
```

In the Testcontainers registration, derive the worktree identifier before Spring/Flyway starts and print:

```text
AGENT_TEST_DB_HOST=<container host>
AGENT_TEST_DB_NAME=myapp_worktree_ai_agent_architecture_refactor
```

- [ ] **Step 2: Run the migration test and verify V10 is missing**

Run from `joysong-server`:

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest'
```

Expected: FAIL because Flyway ends at V9 and the V2 schema is absent.

- [ ] **Step 3: Add the Agent-only V10 migration**

The migration must disable foreign-key checks only around the approved Agent table rebuild and re-enable them in the same script. Drop in child-to-parent order, then create in parent-to-child order:

```sql
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS agent_plan_items;
DROP TABLE IF EXISTS agent_plans;
DROP TABLE IF EXISTS agent_safety_events;
DROP TABLE IF EXISTS agent_assessments;
DROP TABLE IF EXISTS agent_user_profiles;
DROP TABLE IF EXISTS agent_messages;
DROP TABLE IF EXISTS agent_turns;
DROP TABLE IF EXISTS agent_sessions;
DROP TABLE IF EXISTS agent_tool_audits;
SET FOREIGN_KEY_CHECKS = 1;
```

Create all eight tables. The critical execution columns and constraints are:

```sql
CREATE TABLE agent_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    persona VARCHAR(32) NOT NULL,
    context_type VARCHAR(32) NOT NULL,
    context_id VARCHAR(100) NOT NULL DEFAULT '',
    title VARCHAR(100) NOT NULL DEFAULT '',
    next_sequence_no BIGINT NOT NULL DEFAULT 1,
    summary_json JSON NOT NULL,
    summary_updated_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    INDEX idx_agent_session_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_turns (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    running_guard TINYINT GENERATED ALWAYS AS (IF(status = 'RUNNING', 1, NULL)) STORED,
    trace_id VARCHAR(36) NOT NULL,
    error_code VARCHAR(64) NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    model_name VARCHAR(100) NOT NULL DEFAULT '',
    prompt_version VARCHAR(64) NOT NULL DEFAULT '',
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    total_duration_ms BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_agent_turn_status CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT fk_agent_turn_session FOREIGN KEY (session_id) REFERENCES agent_sessions(id),
    CONSTRAINT uk_agent_turn_sequence UNIQUE (session_id, sequence_no),
    CONSTRAINT uk_agent_turn_idempotency UNIQUE (session_id, idempotency_key),
    CONSTRAINT uk_agent_turn_running UNIQUE (session_id, running_guard)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`agent_messages` must use `(session_id, sequence_no)` uniqueness, an Agent-internal FK to `agent_turns`, `JSON metadata_json`, and `content_type` constrained to `TEXT/CATALOG/REPORT`. Message sequence is derived from the Turn sequence: USER is `turn.sequence_no * 2 - 1`, ASSISTANT is `turn.sequence_no * 2`; this gives stable ordering without another session counter. Convert all existing Agent JSON text columns to MySQL `JSON`. Add `currency CHAR(3)` to plan amounts, `rule_version`/`risk_code` to safety records, and `deleted_at` to plans. Do not add a foreign key from any Agent table to a non-Agent table.

- [ ] **Step 4: Add matching JPA entities and repositories**

Define the stable enum and entity:

```kotlin
enum class AgentTurnStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Entity
@Table(name = "agent_turns")
class AgentTurnEntity(
    @Id var id: String,
    @Column(name = "session_id", nullable = false) var sessionId: String,
    @Column(name = "sequence_no", nullable = false) var sequenceNo: Long,
    @Column(name = "idempotency_key", nullable = false) var idempotencyKey: String,
    @Column(name = "request_hash", nullable = false) var requestHash: String,
    @Enumerated(EnumType.STRING) var status: AgentTurnStatus,
    @Column(name = "trace_id", nullable = false) var traceId: String,
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "fallback_used") var fallbackUsed: Boolean = false,
    @Column(name = "model_name") var modelName: String = "",
    @Column(name = "prompt_version") var promptVersion: String = "",
    @Column(name = "started_at") var startedAt: LocalDateTime,
    @Column(name = "completed_at") var completedAt: LocalDateTime? = null,
    @Column(name = "total_duration_ms") var totalDurationMs: Long = 0
)
```

Add a pessimistic write query for sessions and repository methods for idempotency, running status, and succeeded-turn messages. Never map `running_guard` as mutable.

- [ ] **Step 5: Run migration and focused entity tests**

Run:

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest'
.\gradlew.bat test --offline --tests 'com.joysong.server.agent.*'
```

Expected: PASS; migration history ends at V10 and Hibernate mappings validate.

- [ ] **Step 6: Commit the schema boundary**

```powershell
git add joysong-server/src/main/resources/db/migration/V10__rebuild_agent_v2.sql joysong-server/src/main/kotlin/com/joysong/server/agent/entity joysong-server/src/main/kotlin/com/joysong/server/agent/repository joysong-server/src/main/kotlin/com/joysong/server/chat/entity joysong-server/src/main/kotlin/com/joysong/server/chat/repository joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt
git commit -m "feat: rebuild AI agent v2 schema"
```

---

### Task 2: Implement Turn Lifecycle, Bounded Memory, and Deterministic Summary

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Extend Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt`

**Interfaces:**
- Produces: `BeginTurnResult`, `CompletedTurn`, `AgentChatHistoryPort`, `AgentContext`, `AgentSessionSummary`.
- Consumes: Task 1 repositories and entities.

- [ ] **Step 1: Write failing lifecycle tests**

Cover new turn creation, repeated successful idempotency, key/hash conflict, an existing running turn, completion, failure, cancellation, and an expired replay after message pruning. Expired replay returns `IDEMPOTENCY_EXPIRED` and never calls the model. Use these signatures:

```kotlin
sealed interface BeginTurnResult {
    data class Started(val turnId: String, val traceId: String, val sequenceNo: Long) : BeginTurnResult
    data class Replayed(val turn: ChatTurnResult) : BeginTurnResult
    data object InProgress : BeginTurnResult
    data object IdempotencyExpired : BeginTurnResult
}

fun beginTurn(sessionId: String, userId: String, content: String, idempotencyKey: String): BeginTurnResult
fun completeTurn(command: CompleteTurnCommand): ChatTurnResult
fun failTurn(turnId: String, errorCode: String, durationMs: Long)
fun cancelTurn(turnId: String, errorCode: String, durationMs: Long)
```

Assert `failTurn` and `cancelTurn` run in `REQUIRES_NEW` transactions and no test leaves `RUNNING` rows.

- [ ] **Step 2: Write failing context and retention tests**

Define:

```kotlin
data class AgentSessionSummary(
    val schemaVersion: Int = 1,
    val goals: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val entityRefs: Map<String, List<String>> = emptyMap(),
    val unresolvedTopics: List<String> = emptyList(),
    val lastSummarizedSequence: Long = 0
)

interface AgentChatHistoryPort {
    fun load(userId: String, sessionId: String, maxMessages: Int, maxTokens: Int): AgentContext
    fun clear(userId: String, sessionId: String)
}
```

Test that only `SUCCEEDED` turns appear, another user's session is rejected, `summary_json` precedes recent messages, raw risk text is excluded, and pruning keeps at most 20 messages no older than 7 days.

- [ ] **Step 3: Run focused tests and verify failure**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest'
```

Expected: FAIL because the services do not exist.

- [ ] **Step 4: Implement short transactions and stable replay**

`beginTurn` must lock the session, compare SHA-256 `request_hash`, atomically increment `next_sequence_no`, create a `RUNNING` turn and USER message at `turn.sequenceNo * 2 - 1`, then return. `completeTurn` must persist ASSISTANT content at `turn.sequenceNo * 2` plus `metadata_json`, update the summary, mark the turn `SUCCEEDED`, and update the session in one short transaction. External calls are forbidden in this service.

Store enough structured response data in the ASSISTANT `metadata_json` to reconstruct `ChatTurnResult` on idempotent replay:

```json
{
  "intent": "CATALOG_QA",
  "queryTarget": "PROJECT",
  "nextAction": "SHOW_CATALOG",
  "catalogItems": [],
  "catalogReport": null
}
```

- [ ] **Step 5: Implement deterministic context and pruning**

Merge only validated router slots, explicit preferences, and platform entity IDs into `AgentSessionSummary`. Update the summary in the same transaction that identifies messages to prune; only delete messages after the new summary is valid JSON and saved. Never summarize free-form safety evidence.

- [ ] **Step 6: Run unit and MySQL concurrency tests**

Add two real concurrent workers to `AgentV2MySqlIntegrationTest`; assert one running turn per session, distinct committed sequence numbers, stable replay, and no duplicate assistant message.

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest'
```

Expected: PASS.

- [ ] **Step 7: Commit lifecycle and memory**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt
git commit -m "feat: add idempotent agent turn lifecycle"
```

---

### Task 3: Extract Router, Tools, Prompt, and Native Model Gateway

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/router/AgentRouter.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/tool/AgentToolRegistry.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/prompt/PromptAssembler.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/ModelGateway.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/model/NativeOpenAiCompatibleModelGateway.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentCatalogQueryRepository.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt`
- Replace: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/model/ModelGatewayContractTest.kt`

**Interfaces:**
- Produces: transaction-free `AgentRouter.route`, `AgentToolRegistry.execute`, `PromptAssembler.assemble`, and `ModelGateway` used by Task 4.
- Consumes: current routing rules/catalog behavior and Task 2 context types.

- [ ] **Step 1: Write the provider-neutral model contract and failing gateway tests**

```kotlin
interface ModelGateway {
    fun complete(request: ModelRequest): ModelResponse
    fun completeStructured(request: StructuredModelRequest): ModelResponse
    fun stream(request: ModelRequest, observer: ModelStreamObserver): ModelStreamHandle
}

data class ModelRequest(
    val messages: List<ModelMessage>,
    val model: String,
    val maxTokens: Int,
    val temperature: Double,
    val traceId: String
)
```

Use an in-process HTTP server. Test one request, Bearer header, response content/usage, timeout, 429, retryable 5xx, invalid JSON, ordered SSE delta, `[DONE]`, cancellation, and exactly one terminal callback.

- [ ] **Step 2: Move existing router tests before moving implementation**

Move the essential existing `AgentIntentRouterTest` assertions into the routing section of `AgentWorkflowCoreTest`. Add classification failure fallback and local-safety-cannot-downgrade cases. Remove the old test only after the consolidated cases pass.

- [ ] **Step 3: Implement the native gateway by extraction**

Move Chat Completions request/response/SSE parsing from `ChatService` without changing public behavior. Retry only connection errors, timeouts, 429, and configured 5xx within a total deadline. Authentication and malformed requests must never retry.

- [ ] **Step 4: Extract routing, tools, and prompt assembly**

Keep deterministic local routing first. Register only:

```kotlin
enum class AgentToolName { CATALOG_QUERY, SAFETY_POLICY, PLANNING_ENTRY }
```

Each tool returns a typed result and has no arbitrary write access. `PromptAssembler` returns `promptVersion`, messages, evidence, catalog cards/report, and next action. Move the current persona/response policies intact before editing wording.

- [ ] **Step 5: Replace full-table scans with Agent-specific queries**

Add repository methods that filter active/city/name/tag/ID in SQL and return bounded results. Do not change public Discover service APIs. Update plan version allocation to lock the profile and use `MAX(version)+1` across soft-deleted plans.

- [ ] **Step 6: Run focused tests**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest' --tests '*ModelGatewayContractTest' --tests '*AgentCatalogServiceTest'
```

Expected: PASS, with no LangChain4j dependency in `build.gradle.kts`.

- [ ] **Step 7: Commit extracted core components**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent joysong-server/src/test/kotlin/com/joysong/server/agent
git commit -m "refactor: extract agent workflow components"
```

---

### Task 4: Add the Transaction-Free Orchestrator and Minimal Diagnostics

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/AgentTurnOrchestrator.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentDiagnostics.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/diagnostics/AgentDebugRecorder.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/config/AgentProperties.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Delete: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentTraceService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/controller/AgentController.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify local templates if tracked: `joysong-server/.env.example`
- Modify: `.gitignore`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`

**Interfaces:**
- Produces: `AgentTurnOrchestrator.execute` and `stream`, JSON diagnostic events, and optional dev/test debug bundles.
- Consumes: Tasks 2–3 lifecycle, context, tools, prompts, and gateway.

- [ ] **Step 1: Write failing orchestration tests**

Inject fakes for lifecycle, router, tool registry, prompt assembler, gateway, and diagnostics. Assert this sequence:

```text
begin -> context -> route -> tools -> prompt -> model -> complete
```

Assert `TransactionSynchronizationManager.isActualTransactionActive()` is false inside router, tools, and gateway. Timeout/429/invalid structured output must call `failTurn` and never leave `RUNNING`.

- [ ] **Step 2: Write failing diagnostic tests**

Use `@TempDir`. Assert logs contain trace ID, step, status, duration, token counts, and error code but never message text, prompt, gateway URL, Authorization, email, phone, or raw health fields. Assert production configuration rejects debug recorder enablement. Assert files older than 24 hours are removed.

- [ ] **Step 3: Implement typed configuration**

```kotlin
@ConfigurationProperties("agent")
data class AgentProperties(
    val recentMessageLimit: Int = 20,
    val messageRetentionDays: Long = 7,
    val contextTokenBudget: Int = 4_000,
    val debug: Debug = Debug()
) {
    data class Debug(val enabled: Boolean = false, val retentionHours: Long = 24)
}
```

Configure `.runtime/agent-debug` and add `joysong-server/.runtime/` to `.gitignore`. Production startup must fail when `agent.debug.enabled=true`.

- [ ] **Step 4: Implement the orchestrator with no transaction annotation**

`AgentTurnOrchestrator` must never be `@Transactional`. Carry `traceId` explicitly through sync and async callbacks. Catch stable domain exceptions, update Turn terminal state through `TurnLifecycleService`, and emit one terminal diagnostic event.

- [ ] **Step 5: Reduce ChatService to a compatibility façade**

Keep session list/create/delete and message history behavior. Replace `sendMessageInternal`, prompt construction, model HTTP, and trace persistence with delegation to the orchestrator. Remove `AgentTraceService`, `AgentToolAuditEntity`, repository, and `/api/agent/traces`.

- [ ] **Step 6: Run focused tests**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest' --tests '*ModelGatewayContractTest'
```

Expected: PASS; no test writes `.runtime` outside `@TempDir`.

- [ ] **Step 7: Commit orchestration and diagnostics**

```powershell
git add .gitignore joysong-server/src/main joysong-server/src/test/kotlin/com/joysong/server/agent
git commit -m "refactor: orchestrate agent turns outside transactions"
```

---

### Task 5: Preserve REST and SSE Compatibility with Stable Terminal Errors

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/config/AgentExecutionConfig.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/controller/AgentExceptionHandler.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/chat/controller/ChatControllerTest.kt`

**Interfaces:**
- Produces: additive REST fields and consistent `delta/done/error` SSE terminal envelopes consumed by the Flutter plan.
- Consumes: Task 4 orchestrator and diagnostics.

- [ ] **Step 1: Write failing controller tests for old and new clients**

The request and response additions are optional:

```kotlin
data class SendMessageRequest(val content: String, val idempotencyKey: String? = null)

data class AgentTurnRefResponse(val id: String, val status: String)
data class AgentTurnErrorResponse(val code: String, val message: String, val retryable: Boolean)

data class ChatTurnResponse(
    val message: ChatMessageResponse,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val traceId: String? = null,
    val turn: AgentTurnRefResponse? = null,
    val error: AgentTurnErrorResponse? = null
)
```

Test an old `{ "content": "..." }` request, correct 404/409/429/503 HTTP statuses, legacy response fields, enriched response fields, SSE done, SSE error, and one terminal event.

- [ ] **Step 2: Run controller tests and verify failure**

```powershell
.\gradlew.bat test --offline --tests '*ChatControllerTest'
```

Expected: FAIL because current controller embeds status in `BaseResponse.code` and uses the common pool.

- [ ] **Step 3: Add bounded executor and cancellation propagation**

Create a named `ThreadPoolTaskExecutor` with configurable core/max/queue values. Reject overload with stable `AGENT_BUSY`. Register `SseEmitter.onCompletion`, `onTimeout`, and `onError` to invoke the stream cancellation handle and finish the Turn once.

- [ ] **Step 4: Add stable domain errors and proper HTTP mapping**

Map `TURN_IN_PROGRESS` to 409, rate limiting to 429, provider unavailable/timeout to 503, authorization to 403, and missing session to 404. Never return provider exception text.

- [ ] **Step 5: Run controller and orchestrator tests**

```powershell
.\gradlew.bat test --offline --tests '*ChatControllerTest' --tests '*AgentWorkflowCoreTest'
```

Expected: PASS.

- [ ] **Step 6: Commit the transport contract**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/chat joysong-server/src/main/kotlin/com/joysong/server/agent/config joysong-server/src/main/kotlin/com/joysong/server/agent/controller joysong-server/src/test/kotlin/com/joysong/server/chat
git commit -m "feat: stabilize agent REST and SSE terminal states"
```

---

### Task 6: Reduce Regression Scripts and Verify the Backend Deliverable

**Files:**
- Modify: `joysong-server/scripts/mock-llm-gateway.ps1`
- Modify: `joysong-server/scripts/ai-agent-regression.ps1`
- Modify: `docs/AI_AGENT_DEVELOPMENT.md`
- Modify: `docs/AI_AGENT_TESTING.md`
- Modify: `docs/LLM_RELAY_CONFIGURATION.md`

**Interfaces:**
- Produces: six supported smoke scenarios and accurate operations documentation.
- Consumes: completed Tasks 1–5.

- [ ] **Step 1: Add deterministic mock scenarios**

Support `success`, `invalid-json`, `timeout`, `rate-limit`, `server-error`, and `stream-interrupted`. Each mock response must include a stable request ID; no random sleep.

- [ ] **Step 2: Reduce API regression to six scenarios**

Keep only: ordinary chat, catalog lookup, follow-up context, safety question, plan creation, and SSE success/error. Add assertions for `traceId`, terminal Turn state, no duplicate response on repeated idempotency key, and no safety downgrade.

- [ ] **Step 3: Update documentation**

Document eight tables, summary plus 20-message/7-day retention, short transactions, stable errors, dev/test debug bundles, production debug prohibition, and the optional LangChain4j follow-up plan. Remove SQL examples for `agent_tool_audits`.

- [ ] **Step 4: Run the focused verification set**

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest' --tests '*ModelGatewayContractTest' --tests '*ChatControllerTest' --tests 'com.joysong.server.config.OpenAiBaseUrlPolicyTest'
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentV2MySqlIntegrationTest'
```

Expected: all focused tests PASS. If Docker is unavailable, report the MySQL test as unexecuted; do not point it at a shared database.

- [ ] **Step 5: Run diff and isolation checks**

```powershell
git diff --check
git status --short
git diff --name-only master...HEAD
```

Expected: only Agent/chat/config/docs/test files listed by this plan; no changes to unrelated business tables or modules.

- [ ] **Step 6: Commit regression and docs**

```powershell
git add joysong-server/scripts docs/AI_AGENT_DEVELOPMENT.md docs/AI_AGENT_TESTING.md docs/LLM_RELAY_CONFIGURATION.md
git commit -m "test: cover agent v2 critical workflows"
```
