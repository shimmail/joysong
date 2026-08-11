# AI Agent Multi-Label Intent Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace global intent certainty and nearest-term negation with clause-aware, per-label evidence while preserving a compatible primary route for existing consumers.

**Architecture:** `AgentIntentRouter` will scan the whole current message into independent intent and target evidence, apply lightweight bilingual sentence constraints to each match, and select one compatibility decision without discarding other positive labels. Context and LLM output will merge only into unresolved evidence; `ChatService` continues existing primary-route behavior while passing all labels to downstream generation.

**Tech Stack:** Kotlin, Spring Boot, JUnit 5, Gradle, existing OpenAI-compatible parser integration; no new NLP or runtime dependency.

## Global Constraints

- Route order remains current-message keyword matching, bounded context completion, then LLM fallback.
- Current-message explicit labels must never be overridden by history or the model.
- Certainty, polarity, source, and lock state are tracked per label; one ambiguous label cannot unlock another label.
- A message may retain multiple intent and target labels for downstream decisions.
- Existing `AgentIntentDecision.intent` and `queryTarget` remain the compatibility primary route; no REST, persistence, Flutter, or database schema changes.
- Explicitly negated safety states do not trigger `SAFETY_SCREENING`; uncertain safety may be upgraded by the parser; unnegated safety cannot be downgraded.
- Support Chinese and English, including common plural target words, without a new NLP dependency.
- Intent parser retains 3-second connect and 8-second read timeouts and shares only the main Agent API key, base URL, proxy, HTTP security policy, and provider protocol.
- Parser failure or invalid output uses the local best route and does not fail answer generation.
- Run the smallest relevant test first; after focused tests pass, run at most one backend full suite and stop it after ten minutes.

---

### Task 1: Per-label evidence and multi-label current scanning

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`

**Interfaces:**
- Produces `enum class AgentLabelPolarity { POSITIVE, NEGATIVE, UNCERTAIN }`.
- Produces `enum class AgentLabelSource { CURRENT, CONTEXT, MODEL }`.
- Produces `data class AgentIntentEvidence(val intent: AgentIntent, val polarity: AgentLabelPolarity, val explicit: Boolean, val locked: Boolean, val source: AgentLabelSource)`.
- Produces `data class AgentTargetEvidence(val target: AgentQueryTarget, val polarity: AgentLabelPolarity, val explicit: Boolean, val locked: Boolean, val source: AgentLabelSource)`.
- Extends `AgentRouteAssessment` with defaulted `intentEvidence: Set<AgentIntentEvidence> = emptySet()` and `targetEvidence: Set<AgentTargetEvidence> = emptySet()`.
- Produces private `selectPrimary(intentEvidence, targetEvidence, contextType): AgentIntentDecision`; existing `decision`, `assess`, and `decide` remain compatible.

- [ ] **Step 1: Add failing multi-label and independent-lock tests**

Add exact assertions to `AgentIntentRouterTest`:

```kotlin
val safetyComparison = router.assessCurrent("怀孕期间比较两个机构", "GENERAL")
assertEquals(setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.COMPARISON), safetyComparison.positiveIntents())
assertEquals(setOf(AgentQueryTarget.INSTITUTION), safetyComparison.positiveTargets())
assertEquals(AgentIntent.SAFETY_SCREENING, safetyComparison.decision.intent)

val comparisonPlanning = router.assessCurrent("比较这些项目并制定方案", "GENERAL")
assertEquals(setOf(AgentIntent.COMPARISON, AgentIntent.PLANNING), comparisonPlanning.positiveIntents())
assertEquals(AgentIntent.COMPARISON, comparisonPlanning.decision.intent)

val independent = router.assessCurrent("I do not not want a doctor; compare clinics", "GENERAL")
assertTrue(independent.evidenceFor(AgentIntent.COMPARISON).locked)
assertTrue(independent.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
assertEquals(AgentLabelPolarity.UNCERTAIN, independent.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
```

Add test-local helpers or production read-only helpers `positiveIntents`, `positiveTargets`, and overloaded `evidenceFor` with deterministic missing-evidence assertions.

- [ ] **Step 2: Run the router test and observe RED**

Run from `joysong-server`:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest
```

Expected: compilation fails because per-label evidence and multi-label accessors do not exist.

- [ ] **Step 3: Implement evidence collection before primary selection**

Refactor `assessCurrent` so each signal family creates its own evidence and all positive matches survive. Replace cross-family expressions such as `signals.any { it.ambiguousNegation }` with certainty computed from the matching family only. Implement `selectPrimary` with the compatibility order `SAFETY_SCREENING`, detail-context summary, `COMPARISON`, `PLANNING`, `CATALOG_QA`, `GENERAL_CHAT`; retain every non-primary evidence item.

Keep `explicitIntent` and `explicitQueryTarget` as derived compatibility values for only the selected primary values. Do not use them to authorize context or parser replacement in later tasks.

- [ ] **Step 4: Run the router test and observe GREEN**

Run the Task 1 command once. Expected: all router tests pass, including existing single-route assertions.

- [ ] **Step 5: Commit Task 1**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt
git commit -m "refactor: track intent certainty per label"
```

---

### Task 2: Clause-aware bilingual negation constraints

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`

**Interfaces:**
- Consumes Task 1 evidence types.
- Produces private `SentenceConstraint` classification for action negation, state negation, uncertainty phrase, double negation, and clause boundary.
- Replaces distance-based negator-to-next-routing-term binding in `annotateSignals` with same-clause, compatible-family binding.

- [ ] **Step 1: Add failing sentence-constraint tests**

Add bilingual cases with exact evidence assertions:

```kotlin
"Don't compare treatments while pregnant"
// COMPARISON NEGATIVE; SAFETY_SCREENING POSITIVE and locked.

"I don't know if I am pregnant"
// SAFETY_SCREENING UNCERTAIN; requiresLlmParsing true.

"I am not pregnant; compare treatments"
// SAFETY_SCREENING NEGATIVE; COMPARISON and PROJECT POSITIVE and locked.

"不要比较项目，同时我怀孕了"
// COMPARISON NEGATIVE; SAFETY_SCREENING POSITIVE and locked.

"我不确定是否怀孕，想比较机构"
// SAFETY_SCREENING UNCERTAIN; COMPARISON and INSTITUTION remain locked.
```

Also assert `Compare institutions` yields `INSTITUTION`, `What procedures help acne?` yields `PROJECT`, and `hospitality` does not yield `INSTITUTION`.

- [ ] **Step 2: Run only the new router selectors and observe RED**

Run the closest test methods with Gradle `--tests` selectors. Expected: the current nearest-following-term implementation negates or unlocks unrelated evidence, and plural cases fail.

- [ ] **Step 3: Implement lightweight sentence constraints**

Normalize apostrophes, case, and whitespace; preserve offsets. Split hard clauses on punctuation and the known boundary/transition terms `while`, `but`, `instead`, `because`, `同时`, `但是`, `而是`, `因为`. Recognize uncertainty phrases before individual negators: `don't know if`, `do not know whether`, `not sure if`, `not sure whether`, `不确定是否`, `不知道是否`.

Within a clause, bind action negators only to intent action terms and state negators only to safety or target entity terms. Treat repeated compatible negators as `UNCERTAIN`; if no unique compatible term exists, mark only compatible candidates uncertain. Remove the 24-character cross-family fallback and the 8-character scope chaining. Add explicit plural terms `institutions`, `procedures`, `clinics`, `doctors`, and `treatments` while retaining English word boundaries.

- [ ] **Step 4: Run failed selectors, then the complete router class**

First rerun only the failed selectors. After they pass, run `AgentIntentRouterTest` once. Expected: GREEN without repeated already-passing commands.

- [ ] **Step 5: Commit Task 2**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt
git commit -m "fix: scope bilingual negation by sentence pattern"
```

---

### Task 3: Per-label context and parser merging

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`

**Interfaces:**
- Consumes Task 1 evidence collections and Task 2 polarity.
- Extends `ParsedAgentRoute` with defaulted `intents: Set<AgentIntent> = emptySet()` while retaining `intent` compatibility.
- `supplementWithContext` and `mergeParsedRoute` union compatible evidence and only replace evidence whose polarity is `UNCERTAIN` or which is absent.
- `ChatService` parser accepts optional JSON `intents` array and legacy `intent`; schema validation rejects invalid members.

- [ ] **Step 1: Add failing merge tests**

Assert in router tests that context cannot modify locked current `COMPARISON` or `INSTITUTION`, even when `DOCTOR` evidence is uncertain. Assert context fills an absent target without deleting existing intent labels.

Assert in `AgentWorkflowCoreTest` that parser output `{ "intent":"SAFETY_SCREENING", "intents":["SAFETY_SCREENING"], "queryTarget":null }` upgrades uncertain safety in `I am not not pregnant; compare treatments`, while locked `COMPARISON + PROJECT` remain in evidence. Assert an invalid item in `intents` rejects the parser result and final completion still occurs locally.

- [ ] **Step 2: Run the two focused test classes and observe RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest --tests com.joysong.server.agent.AgentWorkflowCoreTest
```

Expected: failures expose global lock checks and target compatibility rejection before safety upgrade.

- [ ] **Step 3: Implement evidence-aware merges and validator ordering**

Make context evidence `source=CONTEXT`, `explicit=false`, `locked=false`; it may fill only absent or uncertain labels. Convert parsed legacy `intent` plus optional `intents` to `source=MODEL` evidence and merge by label. Validate schema and enum membership first, then enforce locked current evidence per label. Evaluate the safety-upgrade exception before target compatibility, so a safety label with no target is valid while locked business target evidence remains intact.

Recompute the compatibility `decision` through `selectPrimary` after every merge. Parser timeout/error/invalid output returns the unchanged local assessment.

- [ ] **Step 4: Rerun failed selectors, then both focused classes**

Expected: GREEN; parser cannot overwrite locked labels and can upgrade only uncertain safety.

- [ ] **Step 5: Commit Task 3**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: merge context and model intent labels independently"
```

---

### Task 4: Downstream multi-label handoff and focused verification

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Verify: `docs/CONFIGURATION_GUIDE.md`
- Verify: `docs/AI_AGENT_DEVELOPMENT.md`
- Verify: `docs/LLM_RELAY_CONFIGURATION.md`

**Interfaces:**
- Consumes final `AgentRouteAssessment.intentEvidence` and `targetEvidence`.
- Existing persistence, REST result, search, generation profile, safety, planning, and report branches continue to consume `decision`.
- Generation prompt receives stable summaries `detectedIntentLabels` and `detectedTargetLabels` containing positive and uncertain evidence but excluding explicitly negative labels.

- [ ] **Step 1: Add failing downstream handoff tests**

In `AgentWorkflowCoreTest`, capture the final completion request for “怀孕期间比较两个机构” and assert its prompt contains both `SAFETY_SCREENING` and `COMPARISON` plus `INSTITUTION`. Assert the primary route remains `SAFETY_SCREENING`. Add “比较这些项目并制定方案” and assert both intent labels reach the prompt while the existing compatibility primary remains `COMPARISON`.

Add assertions that explicitly negative labels are not presented as requested actions, and parser failure still sends locally detected positive labels to completion.

- [ ] **Step 2: Run the core test and observe RED**

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.AgentWorkflowCoreTest
```

Expected: final prompt contains only the primary decision and lacks complete label evidence.

- [ ] **Step 3: Add stable label summaries to the generation prompt**

Serialize ordered enum names, not raw user fragments. Include positive labels as detected actions/targets and uncertain labels in a separate clarification constraint. Keep explicitly negative labels only in the internal prohibition instruction so the model does not execute them. Do not alter REST DTOs, persistence commands, summary schema, search policy, or timeout configuration.

- [ ] **Step 4: Run focused verification once**

Run:

```powershell
.\gradlew.bat test --tests com.joysong.server.agent.service.AgentIntentRouterTest --tests com.joysong.server.agent.AgentWorkflowCoreTest --tests com.joysong.server.config.RestTemplateConfigTest
```

Expected: all related unit tests pass. Verify documentation still states parser timeouts are 3 seconds connect and 8 seconds read, and shared settings are Key, Base URL, proxy, HTTP security policy, and provider protocol.

- [ ] **Step 5: Run repository checks and at most one full backend suite**

Run `git diff --check` and `git status --short`. If focused tests are green and no environment blocker exists, run `.\gradlew.bat test` once, stopping after ten minutes and reporting progress; do not retry environment or flaky failures without new evidence.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: pass multi-intent labels to agent generation"
```

---

### Task 5: Final review and branch readiness

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes all prior task commits.
- Produces clean review evidence and a merge-readiness verdict.

- [ ] **Step 1: Inspect final state**

Run `git status --short`, `git diff --check`, and `git log --oneline --decorate -10`. Confirm only intentional commits exist and no temporary test files remain.

- [ ] **Step 2: Map evidence to every acceptance criterion**

Confirm tests demonstrate multi-label scanning, clause-aware negation, per-label locks, current-message precedence, safe parser upgrade, local parser failure, primary-route compatibility, plural matching, and downstream label handoff. Add no new code unless a criterion lacks a failing test.

- [ ] **Step 3: Request whole-branch code review**

Review the full feature range against the design and this plan. Any Critical or Important finding receives one consolidated fix wave followed by one scoped re-review, following the subagent-driven-development limits.

