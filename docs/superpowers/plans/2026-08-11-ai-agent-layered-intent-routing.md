# AI Agent Layered Intent Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement deterministic Chinese and English intent routing in the order current-message keywords, context-only completion, then a separately configurable LLM fallback.

**Architecture:** Keep `AgentIntentRouter` as the pure routing boundary and add explicit field certainty plus bounded negation-aware matching. `ChatService` will call the router first with the raw message, supplement only missing fields from bounded conversation context, and invoke the existing JSON parser only for unresolved ambiguity. Intent parsing shares all Agent transport settings but may select `OPENAI_INTENT_MODEL`; answer generation continues to use `AI_AGENT_MODEL`.

**Tech Stack:** Kotlin, Spring Boot configuration properties, JUnit 5, Gradle, existing OpenAI-compatible `RestTemplate` integration.

## Global Constraints

- Current-message explicit intent and target must never be overridden by history or the model.
- Route order is current-message keyword matching, bounded context completion, then LLM fallback.
- Support Chinese and English keywords and common finite-scope negation without adding an NLP dependency.
- Explicitly negated safety states do not trigger `SAFETY_SCREENING`; ambiguous negation remains unresolved for fallback.
- Unnegated deterministic safety detection cannot be downgraded by context or a model.
- Intent parsing may use `OPENAI_INTENT_MODEL`; when blank it uses `AI_AGENT_MODEL`.
- Intent parsing shares Agent API key, base URL, proxy, timeouts, HTTP client policy, and provider protocol.
- Intent-model failure or invalid output uses the local best route and does not fail the overall request.
- No REST contract, database migration, Flutter, or runtime dependency changes.
- Run the smallest related test first; after related tests pass, run at most one full backend test suite.

---

### Task 1: Negation-aware current-message routing

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`

**Interfaces:**
- Consumes: `AgentIntent`, `AgentQueryTarget`, `AgentIntentDecision`, and `AgentRouteAssessment`.
- Produces: `fun assessCurrent(query: String, contextType: String): AgentRouteAssessment`; `AgentRouteAssessment.explicitIntent: Boolean`, `explicitQueryTarget: Boolean`, and `requiresContextCompletion: Boolean` that later context/model merging can inspect; existing `assess` and `decide` remain compatible wrappers until Task 2 migrates callers.

- [ ] **Step 1: Add failing bilingual precedence and negation tests**

Add parameterized cases that assert exact decisions for:

```kotlin
Arguments.of("不看医生，推荐机构", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION),
Arguments.of("不要方案，只比较项目", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
Arguments.of("我没有怀孕，想比较项目", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
Arguments.of("I don't want a doctor; show me clinics", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION),
Arguments.of("Don't compare them, just summarize this page", AgentIntent.DETAIL_SUMMARY, null),
Arguments.of("I am not pregnant; compare treatments", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT)
```

Also assert that unnegated Chinese and English pregnancy/allergy/medication phrases still produce `SAFETY_SCREENING`, and that an ambiguous double-negative records an ambiguity reason and requests later parsing.

- [ ] **Step 2: Run the router test and verify the new cases fail**

Run from `joysong-server`:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: FAIL because current substring matching treats negated keywords as positive matches and lacks current-field certainty.

- [ ] **Step 3: Implement normalized finite-scope term matching**

Inside `AgentIntentRouter`, introduce focused private types and helpers:

```kotlin
private data class MatchedSignals(
    val positiveTerms: Set<String>,
    val negatedTerms: Set<String>,
    val ambiguousNegation: Boolean
)

private fun matchSignals(query: String, terms: Set<String>): MatchedSignals
```

Normalize Unicode apostrophes, lowercase English, collapse whitespace, and treat punctuation plus Chinese/English contrast markers (`但`, `但是`, `只`, `而是`, `but`, `instead`, `just`) as scope boundaries. Match known multi-word terms before shorter terms. A negator affects the nearest known term in its clause; do not globally negate the sentence. Keep keyword collections explicit and immutable.

Implement `assessCurrent` so it analyzes raw `query` only. Preserve precedence in this order: unnegated safety, detail summary in detail context, comparison, planning, catalog, general chat. Compute `queryTarget` only from unnegated target terms. Add stable ambiguity codes such as `AMBIGUOUS_NEGATION`, `CONFLICTING_CURRENT_TARGETS`, and `UNRESOLVED_CURRENT_REFERENCE`.

Extend `AgentRouteAssessment` with defaulted fields to preserve source compatibility:

```kotlin
val explicitIntent: Boolean = false,
val explicitQueryTarget: Boolean = false,
val requiresContextCompletion: Boolean = false
```

Set each explicit flag only when the raw current message supplies the corresponding unnegated signal. Set `requiresContextCompletion` when either field needed by the selected route is absent or an unresolved reference remains.

- [ ] **Step 4: Run the router test and verify it passes**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: PASS, including existing routing and safety cases.

- [ ] **Step 5: Commit current-message routing**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt
git commit -m "feat: add negation-aware intent keyword routing"
```

---

### Task 2: Context completes only missing route fields

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`

**Interfaces:**
- Consumes: Task 1 `assessCurrent` and field certainty.
- Produces: `fun supplementWithContext(current: AgentRouteAssessment, contextDecisions: List<AgentIntentDecision>): AgentRouteAssessment`; `ChatService.generateTurn` performs current routing before building any contextual search query.

- [ ] **Step 1: Add failing context merge tests**

Test these exact invariants in `AgentIntentRouterTest`:

```kotlin
// Current explicit target wins.
current = router.assessCurrent("对比机构", "GENERAL")
context = listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
assertEquals(AgentQueryTarget.INSTITUTION, router.supplementWithContext(current, context).decision.queryTarget)

// Context fills a missing target.
current = router.assessCurrent("对比一下", "GENERAL")
context = listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
assertEquals(AgentQueryTarget.DOCTOR, router.supplementWithContext(current, context).decision.queryTarget)
```

Add English equivalents and a conflicting-context case that keeps the local best decision, adds `CONFLICTING_CONTEXT`, and requires model parsing.

- [ ] **Step 2: Run the router test and verify context tests fail**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: FAIL because `supplementWithContext` does not exist.

- [ ] **Step 3: Implement missing-field-only context merge**

Add `supplementWithContext` with these rules:

- Never replace an explicit current intent or target.
- Fill only an unresolved intent or target from the most recent compatible structured decision.
- Ignore context decisions inconsistent with deterministic safety.
- If equally recent candidates conflict, retain the current/local candidate, add `CONFLICTING_CONTEXT`, lower confidence, and set `requiresLlmParsing` unless the route is deterministic safety.
- Rebuild decisions through `validatedDecision` so `searchCatalog` and `nextAction` remain consistent.

In `ChatService.generateTurn`, replace the pre-routing call to `contextualSearchQuery(content, previousUserQueries)` with:

```kotlin
val currentAssessment = agentIntentRouter.assessCurrent(content, session.contextType)
val contextDecisions = boundedContextDecisions(historyMessages, summary)
val routeAssessment = if (currentAssessment.requiresContextCompletion) {
    agentIntentRouter.supplementWithContext(currentAssessment, contextDecisions)
} else currentAssessment
```

Implement `boundedContextDecisions` by preferring validated structured topics from `AgentSessionSummary.unresolvedTopics`, then at most the latest four user messages when structured topics are absent. The message fallback is assessed independently; never concatenate it with `content` for routing.

- [ ] **Step 4: Run router tests and compile the affected service**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: PASS and Kotlin compilation succeeds.

- [ ] **Step 5: Commit context-only completion**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt
git commit -m "feat: complete missing intents from bounded context"
```

---

### Task 3: Separate intent model selection with shared transport

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/config/AiAgentProperties.kt`
- Modify: `joysong-server/src/main/resources/application.yml`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/config/RestTemplateConfigTest.kt`

**Interfaces:**
- Consumes: existing `AiAgentProperties.model`, `apiKey`, `baseUrl`, `proxyUrl`, HTTP budgets, and `agentLlmRestTemplate`.
- Produces: `AiAgentProperties.intentModel: String`; `fun resolvedIntentModel(): String`; the intent request body uses that resolved model while completion uses `model`.

- [ ] **Step 1: Add failing configuration and request-model tests**

Add unit assertions:

```kotlin
assertEquals("intent-small", AiAgentProperties(model = "answer-model", intentModel = "intent-small").resolvedIntentModel())
assertEquals("answer-model", AiAgentProperties(model = "answer-model", intentModel = " ").resolvedIntentModel())
```

Add a ChatService core test that forces ambiguous routing, captures both OpenAI-compatible request bodies, and asserts the parser body contains `"model":"intent-small"` while the final generation body contains `"model":"answer-model"`. Assert the same injected `RestTemplate` is used and no second base URL/API-key/proxy property exists.

- [ ] **Step 2: Run the smallest configuration/core tests and verify failure**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest --tests com.joysong.server.config.RestTemplateConfigTest
```

Expected: FAIL because `intentModel` and `resolvedIntentModel` do not exist and parser requests still use the main model.

- [ ] **Step 3: Implement model-name-only override**

Extend `AiAgentProperties`:

```kotlin
var intentModel: String = ""

fun resolvedIntentModel(): String = intentModel.trim().ifBlank { model.trim() }
```

Map it in `application.yml`:

```yaml
intent-model: ${OPENAI_INTENT_MODEL:}
```

Change only the parser request body to use `aiAgentProperties.resolvedIntentModel()`. Keep its RestTemplate, authorization header, endpoint construction, proxy, timeout budgets, and response parsing unchanged. Keep final generation on `aiAgentProperties.model`.

- [ ] **Step 4: Run the configuration/core tests and verify pass**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest --tests com.joysong.server.config.RestTemplateConfigTest
```

Expected: PASS.

- [ ] **Step 5: Commit separate intent model selection**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/config/AiAgentProperties.kt joysong-server/src/main/resources/application.yml joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt joysong-server/src/test/kotlin/com/joysong/server/config/RestTemplateConfigTest.kt
git commit -m "feat: configure AI intent parsing model"
```

---

### Task 4: LLM merge constraints and local failure fallback

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`

**Interfaces:**
- Consumes: Task 2 supplemented route and Task 3 resolved intent model.
- Produces: `fun mergeParsedRoute(local: AgentRouteAssessment, parsed: ParsedAgentRoute?): AgentIntentDecision`; parser failure returns `null` and generation proceeds with the local decision.

- [ ] **Step 1: Add failing model merge and failure-fallback tests**

Cover four cases:

- Explicit `COMPARISON + INSTITUTION` remains unchanged when the parser returns `PLANNING + DOCTOR`.
- Missing target in `COMPARISON` is filled when the parser returns `COMPARISON + PROJECT`.
- Unnegated local `SAFETY_SCREENING` remains safety when the parser returns general chat.
- Parser timeout, non-2xx, malformed JSON, or illegal enum returns the local best route and still performs the normal answer-generation call.

Assert the parser is not called for a complete high-confidence current route.

- [ ] **Step 2: Run the core test and verify the new cases fail**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest
```

Expected: FAIL because current parsed routes can replace whole decisions and parser failures do not yet have the required local continuation behavior.

- [ ] **Step 3: Implement constrained parsing and merging**

Build the parser prompt from the current raw message, bounded context, allowed enums, local decision, and explicit locked fields. Validate parsed enums before merging. Implement `mergeParsedRoute` so it fills only unlocked fields and rebuilds the result using `validatedDecision`.

Wrap only the intent-parser call and parsing in a recoverable boundary. On timeout, provider error, malformed payload, or invalid enum, emit a redacted classification/fallback diagnostic and return `null`. Do not catch failures from the final answer-generation call; its existing `503` behavior remains unchanged.

- [ ] **Step 4: Run core and router tests and verify pass**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: PASS.

- [ ] **Step 5: Commit constrained model fallback**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: constrain AI intent fallback merging"
```

---

### Task 5: Configuration documentation and focused integration verification

**Files:**
- Modify: `docs/CONFIGURATION_GUIDE.md`
- Modify: `docs/AI_AGENT_DEVELOPMENT.md`
- Modify: `docs/LLM_RELAY_CONFIGURATION.md`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: completed route pipeline and `OPENAI_INTENT_MODEL` behavior.
- Produces: operator-facing configuration contract and one end-to-end proof using the isolated MySQL test database.

- [ ] **Step 1: Add focused integration assertions**

Enable the intent parser in a focused integration fixture and configure `ai-agent.intent-model=intent-test-model`. Send an ambiguous bilingual/context-dependent request and assert:

- first request body uses `intent-test-model`;
- second request body uses `test-model`;
- an explicit current target conflicting with history bypasses or constrains the parser and persists the current target;
- a simulated parser failure still completes the turn using the local route;
- the test container prints `AGENT_CHAT_TEST_DB_HOST` and a database name beginning `myapp_worktree_` before Flyway runs.

- [ ] **Step 2: Run the focused MySQL integration test once**

From `joysong-server`, first print the resolved isolation values, then run only the class:

```powershell
$worktreeId = (Split-Path (Split-Path $PWD -Parent) -Leaf) -replace '[^A-Za-z0-9]+','_'
$databaseName = "myapp_worktree_$($worktreeId.ToLower())"
Write-Host "DB_HOST=Testcontainers-managed"
Write-Host "DB_NAME=$databaseName"
.\gradlew.bat test --tests com.joysong.server.agent.AgentChatFlowIntegrationTest
```

Expected: PASS; output shows an isolated database whose name begins `myapp_worktree_`.

- [ ] **Step 3: Document the routing order and model variable**

Document `OPENAI_INTENT_MODEL` next to `OPENAI_INTENT_PARSER_ENABLED` in all three files. State that blank means `AI_AGENT_MODEL`, only the model name differs, parser failures use the local route, and final generation always uses `AI_AGENT_MODEL`. Update the routing description to current keywords, bounded context, then model fallback, including Chinese/English negation behavior.

- [ ] **Step 4: Run the complete related unit-test set once**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest --tests com.joysong.server.agent.AgentWorkflowCoreTest --tests com.joysong.server.config.RestTemplateConfigTest
```

Expected: PASS. Do not rerun any already-passing command.

- [ ] **Step 5: Optionally run one backend full suite**

Only after all focused tests pass, run at most once:

```powershell
.\gradlew.bat test
```

Stop if it exceeds ten minutes and report progress plus the slowest observed test. Do not retry flaky or environment failures without new evidence.

- [ ] **Step 6: Commit docs and integration coverage**

```powershell
git add -- docs/CONFIGURATION_GUIDE.md docs/AI_AGENT_DEVELOPMENT.md docs/LLM_RELAY_CONFIGURATION.md joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt
git commit -m "docs: describe layered AI intent routing"
```

---

### Task 6: Final verification and review handoff

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: clean worktree, verification evidence, and review-ready commit range.

- [ ] **Step 1: Inspect the final diff and repository state**

Run:

```powershell
git status --short
git diff --check
git log --oneline --decorate -8
```

Expected: no unstaged changes, no whitespace errors, and the task commits are present.

- [ ] **Step 2: Verify spec coverage explicitly**

Confirm from tests and diff that current-message precedence, bounded context completion, bilingual negation, negated safety, separate intent model selection, shared transport, constrained model merge, and local parser-failure fallback are each implemented. If any item lacks evidence, add the smallest failing test and implementation before claiming completion.

- [ ] **Step 3: Request code review**

Invoke `superpowers:requesting-code-review` with the design, this plan, commit range, and exact verification output. Address only findings within this feature scope.
