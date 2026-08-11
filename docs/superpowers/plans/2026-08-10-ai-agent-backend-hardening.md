# AI Agent Backend Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the synchronous Agent backend recoverable, explicitly enabled, and safely configured for the FastAIToken production relay.

**Architecture:** Preserve V10 and add a forward-only lease column (renumbered to V15 during master integration). Centralize provider settings in `AiAgentProperties`, enforce availability before a Turn is created, and keep recovery inside the existing session-locked `beginTurn` transaction.

**Tech Stack:** Kotlin, Spring Boot, Spring Data JPA, Flyway, MySQL 8, JUnit 5, MockK.

## Global Constraints

- Work only on `codex/ai-agent-production-hardening` in `D:\code\kotlin\joysong\.worktrees\ai-agent-production-hardening`.
- Never modify `V10__rebuild_agent_v2.sql`; it has already been applied.
- FastAIToken production host is exactly `www.fastaitoken.com`, HTTPS only.
- Keep synchronous REST; do not add SSE, queues, heartbeats, Redis, LangChain4j, or autonomous tools.
- Write each behavior test first and observe its expected failure before production edits.
- Do not connect tests or migrations to a shared development database.

---

### Task 1: Add a forward-only Turn lease migration

**Files:**
- Create: `joysong-server/src/main/resources/db/migration/V15__add_agent_turn_lease.sql`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentTurnEntity.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt`

**Interfaces:**
- Produces: nullable database column and entity property `leaseExpiresAt: LocalDateTime?`.
- Consumes: existing `agent_turns.started_at` and V10 schema.

- [ ] **Step 1: Add failing migration assertions**

Add assertions to both the fresh-schema and migration-upgrade fixtures:

```kotlin
val leaseColumn = jdbcTemplate.queryForObject(
    """select count(*) from information_schema.columns
       where table_schema = database()
         and table_name = 'agent_turns'
         and column_name = 'lease_expires_at'""",
    Long::class.java
)
assertThat(leaseColumn).isEqualTo(1)
```

- [ ] **Step 2: Run the isolated migration test and verify RED**

Run from `joysong-server` after printing the resolved host/database:

```powershell
$worktreeId = 'worktree_ai_agent_production_hardening'
$env:AGENT_TEST_DB_NAME = "myapp_$worktreeId"
Write-Host "Agent test database host: Testcontainers"
Write-Host "Agent test database name: $env:AGENT_TEST_DB_NAME"
.\gradlew.bat mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest'
```

Expected: FAIL because `lease_expires_at` is absent.

- [ ] **Step 3: Add V15 and entity mapping**

Create the migration:

```sql
ALTER TABLE agent_turns
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER started_at,
    ADD INDEX idx_agent_turn_lease (status, lease_expires_at);
```

Add to `AgentTurnEntity`:

```kotlin
@Column(name = "lease_expires_at")
var leaseExpiresAt: LocalDateTime? = null,
```

- [ ] **Step 4: Run the isolated migration test and verify GREEN**

Run the Step 2 command. Expected: PASS on a new empty database and the already-V10 fixture.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/resources/db/migration/V15__add_agent_turn_lease.sql joysong-server/src/main/kotlin/com/joysong/server/agent/entity/AgentTurnEntity.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt
git commit -m "feat: add agent turn lease migration"
```

### Task 2: Recover expired RUNNING turns atomically

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/repository/AgentTurnRepository.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentV2MySqlIntegrationTest.kt`

**Interfaces:**
- Consumes: `AiAgentProperties.turnLease` from Task 3; until Task 3 lands, inject a named `Duration` constructor value in tests.
- Produces: `beginTurn` finalizes expired RUNNING turns with `errorCode="STALE_RECOVERED"` before creating one successor.

- [ ] **Step 1: Write failing unit tests for fresh and stale leases**

Cover these observable cases:

```kotlin
@Test fun `active running turn remains in progress`() { /* lease is after clock.now() */ }
@Test fun `expired running turn is failed and a successor starts`() { /* assert old FAILED and new Started */ }
```

Use a fixed `Clock` and assert that a newly created turn has `leaseExpiresAt == now.plus(turnLease)`.

- [ ] **Step 2: Run the focused unit class and verify RED**

```powershell
.\gradlew.bat test --offline --tests '*AgentWorkflowCoreTest'
```

Expected: FAIL because leases are neither assigned nor reclaimed.

- [ ] **Step 3: Implement minimal lease recovery**

Inject `Clock` and `Duration`, then use one helper inside the existing session lock:

```kotlin
private fun recoverIfExpired(turn: AgentTurnEntity, now: LocalDateTime): Boolean {
    val expiresAt = turn.leaseExpiresAt ?: return false
    if (expiresAt.isAfter(now)) return false
    turn.status = AgentTurnStatus.FAILED
    turn.errorCode = "STALE_RECOVERED"
    turn.completedAt = now
    turn.totalDurationMs = Duration.between(turn.startedAt, now).toMillis().coerceAtLeast(0)
    turnRepository.save(turn)
    return true
}
```

Call it for both the same-key RUNNING Turn and another RUNNING Turn. Flush the recovered Turn before inserting its successor so the generated `running_guard` unique constraint is released.

- [ ] **Step 4: Add and run the real concurrency test**

Add a MySQL integration case where two requests race after an expired Turn. Assert exactly one `Started`, one `InProgress`, one successor RUNNING Turn, and the old Turn is `FAILED/STALE_RECOVERED`.

```powershell
$env:AGENT_TEST_DB_NAME='myapp_worktree_ai_agent_production_hardening'
Write-Host 'Agent test database host: Testcontainers'
Write-Host "Agent test database name: $env:AGENT_TEST_DB_NAME"
.\gradlew.bat mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent joysong-server/src/test/kotlin/com/joysong/server/agent
git commit -m "fix: recover stale agent turns"
```

### Task 3: Centralize FastAIToken configuration and isolate the proxy

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/config/AiAgentProperties.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicy.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/RestTemplateConfig.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/ConfigValidator.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/resources/application-prod.yml`
- Modify: `joysong-server/.env.example`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/config/OpenAiBaseUrlPolicyTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/config/AiAgentPropertiesTest.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/config/RestTemplateConfigTest.kt`

**Interfaces:**
- Produces: `@ConfigurationProperties("ai-agent") data class AiAgentProperties(...)` and exact-host policy.
- Consumes: environment variables `AI_AGENT_ENABLED`, `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `AI_AGENT_MODEL`, `OPENAI_PROXY_URL`, `AI_AGENT_TURN_LEASE_SECONDS`.

- [ ] **Step 1: Write failing policy and production-validation tests**

```kotlin
assertThat(OpenAiBaseUrlPolicy.isAllowed("https://www.fastaitoken.com/v1")).isTrue()
assertThat(OpenAiBaseUrlPolicy.isAllowed("https://api.openai.com/v1")).isFalse()
assertThat(OpenAiBaseUrlPolicy.isAllowed("https://www.fastaitoken.com.evil.test/v1")).isFalse()
assertThat(OpenAiBaseUrlPolicy.isAllowed("http://www.fastaitoken.com/v1")).isFalse()
```

Add property validation cases proving enabled production rejects blank key, URL, and model while disabled production accepts blank provider credentials.

- [ ] **Step 2: Run config tests and verify RED**

```powershell
.\gradlew.bat test --offline --tests '*OpenAiBaseUrlPolicyTest' --tests '*AiAgentPropertiesTest' --tests '*RestTemplateConfigTest'
```

Expected: FAIL because FastAIToken is not allowed, typed properties do not exist, and AI uses `google.proxy-url`.

- [ ] **Step 3: Add typed properties and exact host validation**

Use this public shape:

```kotlin
@ConfigurationProperties("ai-agent")
data class AiAgentProperties(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var baseUrl: String = "",
    var model: String = "",
    var proxyUrl: String = "",
    var turnLease: Duration = Duration.ofSeconds(90),
    var intentParserEnabled: Boolean = true,
    var demoFallbackEnabled: Boolean = false
)
```

Map existing environment variables in `application.yml`, set the production base URL example to `https://www.fastaitoken.com/v1`, and make `OpenAiBaseUrlPolicy` accept only the exact lowercase host.

- [ ] **Step 4: Isolate `OPENAI_PROXY_URL` without changing translation routing**

Add Agent-specific beans `agentLlmRestTemplate` and `agentIntentParserRestTemplate` that consume `AiAgentProperties.proxyUrl`, and inject those qualifiers into `ChatService`. Do not repurpose the existing shared `llmRestTemplate` until `TranslationService` has its own reviewed network policy. Keep Google-only HTTP clients on `google.proxy-url`; do not copy credentials between settings.

- [ ] **Step 5: Run config tests and verify GREEN**

Run Step 2. Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/config joysong-server/src/main/resources joysong-server/.env.example joysong-server/src/test/kotlin/com/joysong/server/config
git commit -m "feat: harden FastAIToken agent configuration"
```

### Task 4: Enforce `AI_AGENT_ENABLED` before Turn creation

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/AiAgentAvailabilityGuard.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/AgentChatException.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Produces: `fun requireGenerationEnabled()` throwing stable `AGENT_DISABLED`.
- Consumes: `AiAgentProperties.enabled` and provider fields from Task 3.

- [ ] **Step 1: Write a failing disabled-module integration test**

Configure `AI_AGENT_ENABLED=false`, POST a valid message, and assert:

```kotlin
andExpect(status().isServiceUnavailable)
andExpect(jsonPath("$.code").value("AGENT_DISABLED"))
assertThat(turnRepository.count()).isZero()
assertThat(fakeGateway.requestCount).isZero()
```

- [ ] **Step 2: Run the focused test and verify RED**

```powershell
.\gradlew.bat test --offline --tests '*AgentChatFlowIntegrationTest*disabled*'
```

Expected: FAIL because the request currently enters `beginTurn`.

- [ ] **Step 3: Implement one availability guard**

```kotlin
@Component
class AiAgentAvailabilityGuard(private val properties: AiAgentProperties) {
    fun requireGenerationEnabled() {
        if (!properties.enabled) throw AgentChatException.disabled()
    }
}
```

Call it as the first statement of `ChatService.sendMessage`, before content context or Turn persistence. Replace scattered provider `@Value` reads with `AiAgentProperties` access without changing provider request semantics.

- [ ] **Step 4: Run the Agent flow and config tests**

```powershell
.\gradlew.bat test --offline --tests '*AgentChatFlowIntegrationTest' --tests '*AgentWorkflowCoreTest' --tests '*AiAgentPropertiesTest' --tests '*OpenAiBaseUrlPolicyTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server joysong-server/src/test/kotlin/com/joysong/server/agent
git commit -m "feat: add AI agent availability guard"
```

### Task 5: Downgrade planning to transparent information reference

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentPlanServiceTest.kt`

**Interfaces:**
- Produces: information-reference copy, hard exclusion of profile `excludedProjects`, and `需向机构确认` for unknown recovery/pain/risk data.
- Does not produce: suitability scores or claims that recovery/pain preferences affected ranking.

- [ ] **Step 1: Write failing language and exclusion tests**

```kotlin
assertThat(result.items.map { it.projectId }).doesNotContain(excludedProjectId)
assertThat(result.summary).contains("信息参考").contains("不构成诊断或治疗建议")
assertThat(result.summary).doesNotContain("为你制定").doesNotContain("最适合")
assertThat(result.items.single().recoveryNote).isEqualTo("需向机构确认")
```

- [ ] **Step 2: Run the focused class and verify RED**

```powershell
.\gradlew.bat test --offline --tests '*AgentPlanServiceTest'
```

Expected: FAIL on existing personalized copy or missing conservative fields.

- [ ] **Step 3: Make the minimal information-only change**

Remove claims that unused recovery/pain values influence ranking. Apply `excludedProjects` before candidate presentation. Use fixed safety copy and explicit unknown labels; do not infer medical facts from free text.

- [ ] **Step 4: Run the focused service and flow tests**

```powershell
.\gradlew.bat test --offline --tests '*AgentPlanServiceTest' --tests '*AgentProfileServiceTest' --tests '*AgentSafetyServiceTest' --tests '*AgentChatFlowIntegrationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentPlanService.kt joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentPlanServiceTest.kt
git commit -m "fix: limit agent plans to information reference"
```
