# AI Agent Release Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the hardened information assistant on isolated MySQL and document a reversible FastAIToken gray release.

**Architecture:** Use Testcontainers with a worktree-derived database name for fresh and already-V10 paths, add a read-only deployment preflight for legacy V10 risk, and keep production enablement as an explicit operator action.

**Tech Stack:** Gradle, JUnit 5, Testcontainers MySQL 8, PowerShell, Flutter tooling, Markdown runbooks.

## Global Constraints

- Database name: `myapp_worktree_ai_agent_production_hardening`.
- Docker Compose project: `myapp-worktree-ai-agent-production-hardening`.
- Print resolved database host and name before every migration run.
- Never connect to a shared development database.
- Never drop/reset a database unless its name begins with `myapp_worktree_`.
- Run the smallest related tests first and at most one full test suite after they pass.
- Do not enable real production traffic or print FastAIToken credentials.

---

### Task 1: Add a V10 deployment preflight

**Files:**
- Create: `joysong-server/deploy/preflight-agent-v10.ps1`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentMigrationPreflightTest.kt`
- Modify: `docs/AI_AGENT_TESTING.md`

**Interfaces:**
- Produces: a script that exits 0 for absent/empty legacy tables and non-zero when V10 is pending and any legacy Agent table contains rows.
- Consumes: explicit `-DatabaseHost`, `-DatabaseName`, `-DatabaseUser`; password only from `DB_PASSWORD`.

- [ ] **Step 1: Write a failing process-level test**

Create isolated schemas representing empty legacy tables and populated legacy tables. Invoke the script and assert:

```kotlin
assertThat(emptyResult.exitCode).isZero()
assertThat(populatedResult.exitCode).isNotZero()
assertThat(populatedResult.output).contains("V10 migration blocked")
assertThat(populatedResult.output).doesNotContain(databasePassword)
```

- [ ] **Step 2: Run the focused integration test and verify RED**

```powershell
$env:AGENT_TEST_DB_NAME='myapp_worktree_ai_agent_production_hardening'
Write-Host 'Agent test database host: Testcontainers'
Write-Host "Agent test database name: $env:AGENT_TEST_DB_NAME"
.\gradlew.bat mysqlIntegrationTest --offline --tests '*AgentMigrationPreflightTest'
```

Expected: FAIL because the script does not exist.

- [ ] **Step 3: Implement the bounded preflight**

The script must first validate that `DatabaseName` starts with `myapp_worktree_` when invoked in test mode. It reads `flyway_schema_history` to determine whether version 10 is pending, counts only the known legacy `agent_*` tables that exist, prints host/name but no password, and exits before Flyway if any count is non-zero.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run Step 2. Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add joysong-server/deploy/preflight-agent-v10.ps1 joysong-server/src/test/kotlin/com/joysong/server/agent/AgentMigrationPreflightTest.kt docs/AI_AGENT_TESTING.md
git commit -m "chore: guard legacy agent migration"
```

### Task 2: Run the isolated backend release gate

**Files:**
- Modify only if failures reveal an in-scope defect; otherwise none.

**Interfaces:**
- Consumes: completed backend tasks and the isolated database naming contract.
- Produces: captured command evidence for migration, flow, concurrency, idempotency, and configuration behavior.

- [ ] **Step 1: Print the resolved isolation values**

```powershell
$worktreeId='worktree_ai_agent_production_hardening'
$env:AGENT_TEST_DB_NAME="myapp_$worktreeId"
$composeProject='myapp-worktree-ai-agent-production-hardening'
Write-Host "Docker Compose project: $composeProject"
Write-Host 'Database host: Testcontainers'
Write-Host "Database name: $env:AGENT_TEST_DB_NAME"
if (-not $env:AGENT_TEST_DB_NAME.StartsWith('myapp_worktree_')) { throw 'Unsafe database name' }
```

Before the run, replace the obsolete hard-coded database-name assertion in `AgentV2MySqlIntegrationTest.kt` with the value derived by its existing `worktreeDatabaseName()` helper. The assertion must expect `myapp_worktree_ai_agent_production_hardening` in this worktree and remain dynamic in future worktrees.

- [ ] **Step 2: Run fresh MySQL migration and Agent flow tests once**

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest' --tests '*AgentChatFlowIntegrationTest' --tests '*AgentMigrationPreflightTest'
```

Expected: PASS, with resolved host/name printed before migration.

- [ ] **Step 3: Run focused non-DB backend tests once**

```powershell
.\gradlew.bat test --offline --rerun-tasks --tests '*AgentWorkflowCoreTest' --tests '*AgentPlanServiceTest' --tests '*AgentProfileServiceTest' --tests '*AgentSafetyServiceTest' --tests '*OpenAiBaseUrlPolicyTest' --tests '*AiAgentPropertiesTest' --tests '*RestTemplateConfigTest'
```

Expected: PASS.

- [ ] **Step 4: Record failures without retry loops**

For an environment or flaky failure, record the exact class, method, exception, database host/name, and duration. Re-run only the failed method after an in-scope fix; do not repeat a passing command.

### Task 3: Run Flutter verification and write the gray-release runbook

**Files:**
- Create: `docs/AI_AGENT_ROLLOUT.md`
- Modify: `docs/AI_AGENT_DEVELOPMENT.md`
- Modify: `docs/LLM_RELAY_CONFIGURATION.md`
- Modify: `docs/CONFIGURATION_GUIDE.md`

**Interfaces:**
- Produces: operator instructions for `AI_AGENT_ENABLED`, FastAIToken canary, monitored rollout, and immediate disable.

- [ ] **Step 1: Run Flutter Agent tests and analysis**

```powershell
flutter test test/features/agent
flutter analyze lib/features/agent lib/features/shell/presentation/app_shell.dart
```

Expected: PASS. If Flutter is unavailable, report the missing SDK as an environment blocker rather than claiming verification.

- [ ] **Step 2: Write the runbook with exact gates**

Document these operator actions:

```text
1. Deploy with AI_AGENT_ENABLED=false.
2. Validate health and database migration.
3. Run one secret-safe FastAIToken canary using the configured model.
4. Enable for an internal cohort only.
5. Monitor request success rate, provider 401/429/5xx, p95 latency, stale recoveries, and duplicate-turn count.
6. Disable immediately on credential errors, sustained provider failure, duplicate turns, or unsafe medical copy.
7. Rollback uses AI_AGENT_ENABLED=false first; schema V11 remains forward-compatible and is not dropped.
```

Align all model variables on `AI_AGENT_MODEL`, all relay examples on `https://www.fastaitoken.com/v1`, and all AI proxy examples on `OPENAI_PROXY_URL`.

- [ ] **Step 3: Check documentation and worktree scope**

```powershell
git diff --check
git status --short
git diff --name-only master...HEAD
```

Expected: no whitespace errors and no unrelated modules.

- [ ] **Step 4: Run at most one backend full suite**

Only after all targeted backend tests pass:

```powershell
.\gradlew.bat test --offline
```

Stop if it exceeds ten minutes. Report progress and slow tests; do not rerun the full suite.

- [ ] **Step 5: Commit documentation**

```powershell
git add docs/AI_AGENT_ROLLOUT.md docs/AI_AGENT_DEVELOPMENT.md docs/LLM_RELAY_CONFIGURATION.md docs/CONFIGURATION_GUIDE.md
git commit -m "docs: add AI agent gray release runbook"
```
