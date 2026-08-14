# AI Agent ComparisonRequest Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn a recognized comparison intent into durable, message-bound comparison state, constrain answers and reports to confirmed operands, and render incomplete guidance or a target-specific comparison table in Flutter.

**Architecture:** Add a deterministic Kotlin `ComparisonRequestBuilder` over the existing route, loaded history, detail context, and single catalog result. Persist the normalized request and report in the assistant message's existing JSON metadata, project both through REST/SSE/history, and let Flutter render from each message. Complete comparisons retain the current maximum of one intent-model call, one catalog search, and one answer-model call; incomplete comparisons skip the answer model.

**Tech Stack:** Kotlin 1.9, Spring Boot, Jackson, JUnit 5, Testcontainers MySQL, Dart, Flutter widget/unit tests, PlantUML.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\comparison-request-workflow` on `codex/comparison-request-workflow`.
- Reuse `agent_messages.metadata_json`; add no table, column, migration, workflow loop, or model/tool call.
- Only the final primary intent `COMPARISON` may read, create, inherit, or persist comparison state.
- Store only confirmed platform operands. Never promote ranked/popular catalog results into operands unless the current text or detail context identifies them.
- Accept at most four same-type operands, five high-level dimensions, and eight allowlisted constraints.
- Default dimensions are target-specific: institution/doctor omit price; project uses price/rating; institution-project uses price/credentials/rating.
- Never persist raw health or safety text in `constraints`, and never log the request, metadata, or constraints.
- Bind `ComparisonRequest` and `catalogReport` to the assistant message that produced them. Keep only the existing top-level `ChatTurnResponse.catalogReport` as a compatibility field.
- A doctor remains one operand/column even with multiple active practice institutions. Show at most three institutions plus a remainder count and a separate institution-verification summary.
- Missing structured values remain missing; Flutter renders `暂无平台数据 / Not available`. Do not infer them from summaries, biographies, slogans, or detail text.
- Follow project test discipline: run the smallest failing test first, rerun only failed scopes, and run each broader suite at most once.

---

### Task 1: Add the comparison domain model and deterministic builder

**Files:**
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/ComparisonRequest.kt`
- Create: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/ComparisonRequestBuilder.kt`
- Create: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/ComparisonRequestBuilderTest.kt`

**Interfaces:**
- Produces: `ComparisonOperand`, `ComparisonMissingField`, and `ComparisonRequest`.
- Produces: `ComparisonRequestBuilder.build(...)` and the single trusted recovery boundary `normalize(...)`.
- Consumes: current text, routed target, already-returned catalog items, current detail-context items, latest prior request, and already-detected cities.

- [ ] **Step 1: Write failing builder tests**

Add these focused tests using small `AgentCatalogItemResponse` fixtures:

```kotlin
@Test fun `builds a complete request from explicitly named same type candidates`()
@Test fun `does not promote ranked candidates that are absent from the request`()
@Test fun `applies target defaults without making dimensions a missing field`()
@Test fun `completes an incomplete previous request with one current operand`()
@Test fun `does not append one operand to a complete previous request`()
@Test fun `merges dimensions stably and lets current allowlisted constraints win`()
@Test fun `normalizes recovered requests and enforces every capacity limit`()
@Test fun `drops mixed target operands unknown constraints and health text`()
```

Assert exact operand order, target inference, default dimension groups, constraint keys, capacity limits, and recomputed `missingFields`. Assert a candidate present only because of catalog ranking never becomes an operand.

- [ ] **Step 2: Run the builder test and confirm RED**

From `joysong-server`:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.service.ComparisonRequestBuilderTest" --console=plain
```

Expected failure: comparison types and builder do not exist.

- [ ] **Step 3: Add the immutable model**

Create `ComparisonRequest.kt` with the approved wire shape and a derived, non-serialized completeness property:

```kotlin
package com.joysong.server.agent.service

import com.fasterxml.jackson.annotation.JsonIgnore

data class ComparisonOperand(
    val entityType: AgentQueryTarget,
    val entityId: String,
    val displayName: String
)

enum class ComparisonMissingField { OPERANDS, TARGET_TYPE }

data class ComparisonRequest(
    val operands: List<ComparisonOperand> = emptyList(),
    val targetType: AgentQueryTarget? = null,
    val dimensions: List<String> = emptyList(),
    val constraints: Map<String, String> = emptyMap(),
    val missingFields: Set<ComparisonMissingField> = emptySet()
) {
    @get:JsonIgnore
    val isComplete: Boolean get() = missingFields.isEmpty()
}
```

- [ ] **Step 4: Implement bounded build and normalization rules**

Create the service with this public contract:

```kotlin
@Service
class ComparisonRequestBuilder {
    fun build(
        content: String,
        targetType: AgentQueryTarget?,
        candidates: List<AgentCatalogItemResponse>,
        contextCandidates: List<AgentCatalogItemResponse> = emptyList(),
        previous: ComparisonRequest? = null,
        detectedCities: List<String> = emptyList()
    ): ComparisonRequest

    fun normalize(request: ComparisonRequest): ComparisonRequest
}
```

Use fixed configuration inside the builder:

```kotlin
private val allowedDimensions = setOf("PRICE", "CREDENTIALS", "RATING")
private val allowedConstraints = setOf("city", "budgetMin", "budgetMax", "downtimeDays")
private val defaultDimensions = mapOf(
    AgentQueryTarget.INSTITUTION to listOf("CREDENTIALS", "RATING"),
    AgentQueryTarget.DOCTOR to listOf("CREDENTIALS", "RATING"),
    AgentQueryTarget.PROJECT to listOf("PRICE", "RATING"),
    AgentQueryTarget.INSTITUTION_PROJECT to listOf("PRICE", "CREDENTIALS", "RATING")
)
```

Resolve current operands only from case-insensitive full `item.name`/`item.id` matches in `content`, plus all valid `contextCandidates`. Convert `item.type` with `runCatching { AgentQueryTarget.valueOf(item.type.uppercase()) }`. Normalize the inherited value before reading its completeness or collections:

```kotlin
val inherited = previous?.let(::normalize)
```

Merge operands exactly as follows:

```kotlin
val mergedOperands = when {
    currentOperands.size >= 2 -> currentOperands
    currentOperands.isEmpty() -> inherited?.operands.orEmpty()
    inherited != null && !inherited.isComplete && inherited.targetType == resolvedTarget ->
        inherited.operands + currentOperands
    else -> currentOperands
}
```

Infer `resolvedTarget` only from the routed target, a single common current operand type, or the inherited request target. Union inherited and current dimension keywords in stable order; detect only price (`价格/报价/price/cost`), credentials (`资质/认证/credential/verified`), and rating (`评分/评价/rating/review`). If the normalized list is empty and a target exists, apply that target's defaults.

Parse current constraints only from `detectedCities` and bounded numeric patterns for `budgetMin`, `budgetMax`, and `downtimeDays`; current values replace inherited values with the same key. `normalize` must trim strings, remove blanks, preserve insertion order, deduplicate operands by `"${entityType.name}:${entityId}"`, discard mixed target types and unknown keys, enforce the `4/5/8` collection limits and `100/120/40/120` string limits, then derive:

```kotlin
val missing = linkedSetOf<ComparisonMissingField>().apply {
    if (targetType == null) add(ComparisonMissingField.TARGET_TYPE)
    if (targetType == null || operands.map { it.entityId }.distinct().size < 2) {
        add(ComparisonMissingField.OPERANDS)
    }
}
```

- [ ] **Step 5: Run the focused builder test and confirm GREEN**

Run the command from Step 2 once. Expected: all builder tests pass offline.

- [ ] **Step 6: Commit the domain slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/ComparisonRequest.kt joysong-server/src/main/kotlin/com/joysong/server/agent/service/ComparisonRequestBuilder.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/ComparisonRequestBuilderTest.kt
git commit -m "feat: add comparison request builder"
```

### Task 2: Project one catalog result into exact structured comparison evidence

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt:74-308,448-510,565-571`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt`

**Interfaces:**
- Extends: `promptEvidence(..., reportMode: String = "AUTO")`.
- Produces: `filterComparisonEvidence(evidence, request)` with no repository or provider call.
- Produces: target-specific report rows whose `comparisonDimensions` values exactly match item attribute keys.

- [ ] **Step 1: Write failing catalog projection tests**

Add:

```kotlin
@Test fun `comparison evidence preserves operand order and excludes unrelated ranked items`()
@Test fun `comparison evidence contains only structured allowlisted values`()
@Test fun `comparison mode is controlled by routed intent rather than query keywords`()
@Test fun `doctor comparison keeps one doctor column and summarizes all approved practice institutions`()
@Test fun `institution and doctor dimensions never contain price`()
@Test fun `missing structured fields remain absent`()
```

Use at least three ranked items but request only two in reverse order. For the doctor fixture, attach four approved institutions plus an unapproved/deleted relationship; assert one doctor item, three displayed names plus `另有 1 家 / 1 more`, and `verified/total` computed only from active approved relationships.

- [ ] **Step 2: Run the catalog service test and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.service.AgentCatalogServiceTest" --console=plain
```

Expected failure: no exact comparison evidence filter exists and current dimension labels do not match attribute keys.

- [ ] **Step 3: Make routed comparison mode explicit without another search**

Extend the signature and pass the mode into the existing single report call:

```kotlin
fun promptEvidence(
    query: String,
    searchQuery: String = query,
    targetQuery: String = query,
    queryTarget: AgentQueryTarget? = null,
    reportMode: String = "AUTO"
): AgentPromptEvidence {
    val detectedCities = discoverSearchService.citiesMentionedIn(searchQuery)
    val detectedKeywords = catalogContextKeywords(searchQuery).all.toList()
    val explicitlyRequestedTypes = discoverSearchService.explicitlyRequestedEntityTypes(query)
    val reportTarget = queryTarget?.toReportTarget() ?: detectReportTarget(targetQuery)
    val report = report(
        request = AgentCatalogReportRequest(targetQuery, reportMode),
        mentionedCities = detectedCities,
        explicitlyRequestedTypes = explicitlyRequestedTypes,
        searchQuery = searchQuery,
        targetOverride = queryTarget
    )
}
```

Keep the current match/missing-type calculation and `AgentPromptEvidence` return construction below this changed report call; only the routed mode input changes.

The comparison orchestration added later must pass `reportMode = "COMPARISON"`; non-comparison callers keep the default.

- [ ] **Step 4: Use one structured row vocabulary for attributes and dimensions**

Build these localized keys with `AgentText.value(zh, en)` in both item attributes and dimension projection:

```kotlin
private fun comparisonRows(target: ReportTarget?, groups: List<String>): List<String> = when (target) {
    ReportTarget.INSTITUTION -> listOf(
        "城市" to "City", "机构认证" to "Clinic verified", "评分" to "Rating",
        "评价数" to "Review count", "医生数" to "Doctors", "擅长领域" to "Specialties"
    )
    ReportTarget.DOCTOR -> listOf(
        "职称" to "Title", "医生认证" to "Doctor verified", "资质" to "Credentials",
        "擅长领域" to "Specialties", "评分" to "Rating", "评价数" to "Review count",
        "出诊机构" to "Practice institutions", "出诊机构认证" to "Practice institution verification"
    )
    ReportTarget.PROJECT -> listOf(
        "项目分类" to "Category", "项目参考价" to "Reference price",
        "评分" to "Rating", "标签" to "Tags"
    )
    ReportTarget.INSTITUTION_PROJECT -> listOf(
        "城市" to "City", "项目分类" to "Category", "机构价格" to "Clinic price",
        "项目参考价" to "Reference price", "评分" to "Rating", "评价数" to "Review count",
        "销量" to "Sales", "机构认证" to "Clinic verified", "标签" to "Tags"
    )
    null -> emptyList()
}.filter { rowAllowed(target, groups, it) }.map { (zh, en) -> AgentText.value(zh, en) }

private fun rowAllowed(
    target: ReportTarget?,
    groups: List<String>,
    row: Pair<String, String>
): Boolean {
    val requiredGroup = when (row.second) {
        "Clinic price", "Reference price" -> "PRICE"
        "Clinic verified", "Doctor verified", "Credentials",
        "Practice institution verification" -> "CREDENTIALS"
        "Rating", "Review count", "Sales" -> "RATING"
        else -> null
    }
    return requiredGroup == null || requiredGroup in groups
}
```

Implement `rowAllowed` so identity/context rows remain, `PRICE` controls only price rows, `CREDENTIALS` controls verification/credential rows, and `RATING` controls rating/review rows. The target defaults from Task 1 ensure normal output includes the approved rows. Remove slogans, detail summaries, biographies, and other free text from comparison attributes and evidence context.

- [ ] **Step 5: Add exact operand filtering over the in-memory result**

Add:

```kotlin
fun filterComparisonEvidence(
    evidence: AgentPromptEvidence,
    request: ComparisonRequest
): AgentPromptEvidence
```

Build an index from the existing report items, then project in request order:

```kotlin
val dimensions = comparisonRows(request.targetType?.toReportTarget(), request.dimensions)
val index = evidence.report?.items.orEmpty().associateBy { "${it.type.uppercase()}:${it.id}" }
val items = request.operands.mapNotNull { operand ->
    index["${operand.entityType.name}:${operand.entityId}"]
}.take(4).map { item ->
    val structured = linkedMapOf<String, String>()
    dimensions.forEach { key ->
        item.attributes[key]?.takeIf(String::isNotBlank)?.let { structured[key] = it }
    }
    item.copy(summary = "", attributes = structured)
}
```

Return an `AgentPromptEvidence` copy whose `report.mode` is `COMPARISON`, items, dimensions, `matchedEntityIds`, and prompt `context` all come from this same ordered list. The method must not invoke `report`, `promptEvidence`, repositories, discover search, or any provider.

- [ ] **Step 6: Project multi-institution doctors without multiplying columns**

During the existing doctor report construction, keep one `AgentCatalogItemResponse` per doctor ID. Use the already-loaded approved institution memberships, order query-relevant name/city matches first, render at most three `名称（城市）` entries, append `另有 N 家 / N more`, and add a separate `已认证数/总数` attribute. Do not use institution verification as the doctor's own verification and do not add any price attribute.

- [ ] **Step 7: Run the focused catalog test and confirm GREEN**

Run the command from Step 2 once. Expected: all catalog projection tests pass and repository interaction assertions show no second search/filter query.

- [ ] **Step 8: Commit the evidence slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt
git commit -m "feat: constrain comparison catalog evidence"
```

### Task 3: Persist and safely reconstruct message-bound comparison metadata

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt:38-79,162-206,291-317`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt:200-208`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt:596-681,760-802`

**Interfaces:**
- Adds: nullable `comparisonRequest` to `CompleteTurnCommand` and `ChatTurnResult`.
- Replaces card-only parsing with one `AgentMessageProjection` for history and replay.
- Uses: `ComparisonRequestBuilder.normalize()` as the only recovery normalization path.

- [ ] **Step 1: Write failing metadata and replay tests**

Add:

```kotlin
@Test fun `completion persists normalized comparison request and report in assistant metadata`()
@Test fun `non comparison completion discards comparison request before persistence`()
@Test fun `idempotent replay restores the same normalized comparison request and report`()
@Test fun `malformed comparison request does not discard a valid catalog report`()
@Test fun `malformed catalog report does not discard a valid comparison request`()
@Test fun `legacy and malformed root metadata reconstruct with backward compatible defaults`()
```

Parse metadata into `JsonNode`; do not compare raw JSON strings or collection order accidentally. Assert `isComplete` is absent from serialized JSON. Corrupt the two optional nodes independently and assert the valid sibling survives.

- [ ] **Step 2: Run the workflow core test and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.AgentWorkflowCoreTest" --console=plain
```

Expected failure: completion commands and reconstructed turns have no comparison state.

- [ ] **Step 3: Extend completion and turn carriers compatibly**

Append defaulted fields so positional test fixtures keep compiling:

```kotlin
data class CompleteTurnCommand(
    val turnId: String,
    val content: String,
    val intent: String,
    val queryTarget: String?,
    val nextAction: String,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val catalogReport: AgentCatalogReportResponse? = null,
    val durationMs: Long = 0,
    val fallbackUsed: Boolean = false,
    val modelName: String = "",
    val promptVersion: String = "",
    val comparisonRequest: ComparisonRequest? = null
)

data class ChatTurnResult(
    val message: ChatMessageEntity,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val traceId: String? = null,
    val comparisonRequest: ComparisonRequest? = null
)
```

Inject `ComparisonRequestBuilder` into `TurnLifecycleService`.

- [ ] **Step 4: Normalize before writing and centralize metadata projection**

Before the existing planning projection, normalize only comparison turns:

```kotlin
val normalizedCommand = command.copy(
    comparisonRequest = command.comparisonRequest
        ?.takeIf { command.intent.equals("COMPARISON", ignoreCase = true) }
        ?.let(comparisonRequestBuilder::normalize)
)
val persistedCommand = if (normalizedCommand.intent.equals("PLANNING", ignoreCase = true)) {
    normalizedCommand.copy(
        catalogItems = PlanningCatalogProjection.projectItems(normalizedCommand.catalogItems),
        catalogReport = PlanningCatalogProjection.projectReport(normalizedCommand.catalogReport)
    )
} else normalizedCommand
```

Add `"comparisonRequest" to command.comparisonRequest` to the existing metadata map. Add this projection beside the lifecycle service:

```kotlin
data class AgentMessageProjection(
    val message: ChatMessageEntity,
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val comparisonRequest: ComparisonRequest? = null,
    val catalogReport: AgentCatalogReportResponse? = null
)

fun projectMessage(message: ChatMessageEntity): AgentMessageProjection
```

For non-assistant messages, return defaults. Parse the root with `runCatching`; parse catalog items individually; parse `comparisonRequest` and `catalogReport` in separate `runCatching` blocks. Retain a recovered request only when stored intent is `COMPARISON`, then normalize it. Replace `catalogItemsForMessage` and make `reconstruct()` consume exactly one `projectMessage(message)` result.

- [ ] **Step 5: Run the workflow core test and confirm GREEN**

Run the command from Step 2 once. Expected: metadata, malformed-node fallback, and replay tests pass.

- [ ] **Step 6: Commit the persistence slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/orchestration/TurnLifecycleService.kt joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: persist comparison state in assistant metadata"
```

### Task 4: Execute complete and incomplete comparison branches within the existing call budget

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt:69-75,200-224,317-394,482-623,915-1011`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamingService.kt:47-120`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt:345-373,544-681,1237-1294`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt:49-95,356-364`

**Interfaces:**
- Adds: `GeneratedTurn.comparisonRequest` and `GeneratedTurn.answerModelRequired`.
- Adds: `PreparedChatTurn.Completed` for a newly persisted local completion; it is distinct from idempotent replay.
- Refactors: `getSystemPrompt(..., evidence)` so catalog evidence is resolved once before prompt construction.

- [ ] **Step 1: Write failing workflow and call-budget tests**

Add or update:

```kotlin
@Test fun `complete comparison calls catalog once and passes only exact operands to answer model`()
@Test fun `incomplete comparison skips answer model and persists deterministic clarification`()
@Test fun `later comparison restores the latest incomplete request from loaded assistant metadata`()
@Test fun `one current operand does not extend a complete previous request`()
@Test fun `non comparison does not read or persist comparison state`()
@Test fun `comparison does not add an intent or catalog provider call`()
```

Replace the old expectation that a comparison with no named operands invokes the answer model. Assert explicit/high-confidence comparison uses zero intent-model calls, exactly one `promptEvidence`, and one answer call only when complete. Assert ambiguous complete comparison uses one intent call, one catalog call, and one answer call.

In `AgentStreamingServiceTest`, add:

```kotlin
@Test fun `deterministic incomplete comparison emits started and completed without opening provider stream`()
```

- [ ] **Step 2: Run the two focused classes and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.AgentWorkflowCoreTest" --tests "com.joysong.server.agent.AgentStreamingServiceTest" --console=plain
```

Expected failure: comparison still uses ranked evidence, incomplete requests call the answer model, and streaming requires a captured provider profile.

- [ ] **Step 3: Add local-completion state and one completion helper**

Extend carriers:

```kotlin
data class GeneratedTurn(
    val content: String,
    val intentDecision: AgentIntentDecision,
    val llmResult: LlmCallResult,
    val catalogReport: AgentCatalogReportResponse?,
    val catalogItems: List<AgentCatalogItemResponse>,
    val comparisonRequest: ComparisonRequest? = null,
    val answerModelRequired: Boolean = true
)

data class Completed(
    val traceId: String,
    val turnId: String,
    val userMessage: ChatMessageEntity,
    val turn: ChatTurnResult
) : PreparedChatTurn
```

Extract one helper used by synchronous completion, deterministic streaming completion, and normal streaming finalization:

```kotlin
private fun completeGeneratedTurn(
    turnId: String,
    traceId: String,
    sessionId: String,
    startedAt: Long,
    generated: GeneratedTurn
): ChatTurnResult
```

It passes `comparisonRequest = generated.comparisonRequest` into `CompleteTurnCommand`, uses an empty `modelName` when `answerModelRequired` is false, and records completion once.

- [ ] **Step 4: Resolve comparison state after routing and one catalog search**

Inject `ComparisonRequestBuilder`. After the final merged route, read the latest request only for `COMPARISON`:

```kotlin
val previousComparison = if (intentDecision.intent == AgentIntent.COMPARISON) {
    historyMessages.asReversed()
        .asSequence()
        .filter { it.role.equals("ASSISTANT", true) }
        .map { turnLifecycleService.projectMessage(it).comparisonRequest }
        .firstOrNull { it != null }
} else null
```

Resolve raw catalog evidence once:

```kotlin
val currentContextItems = currentContextCatalogItems(session.contextType, session.contextId)
val comparisonSearchQuery = if (intentDecision.intent == AgentIntent.COMPARISON) {
    (listOf(catalogSearchQuery) + currentContextItems.map { it.name })
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
} else catalogSearchQuery
val rawEvidence = catalogEvidence(
    content = content,
    searchQuery = comparisonSearchQuery,
    intentDecision = intentDecision
)
```

`catalogEvidence` calls `agentCatalogService.promptEvidence(...)` only when the existing route requires catalog data and passes `reportMode = "COMPARISON"` only for comparison. It performs no call for general chat/detail-summary paths that currently skip search.

Adding the current detail object's name to the same search query ensures the structured report can contain that confirmed operand without a second lookup. Build the request from raw report items plus `currentContextItems`. If complete, call `filterComparisonEvidence` and pass that exact evidence into prompt construction. If incomplete, return immediately:

```kotlin
GeneratedTurn(
    content = comparisonClarification(request.missingFields),
    intentDecision = intentDecision,
    llmResult = LlmCallResult("", fallbackUsed = false),
    catalogReport = null,
    catalogItems = emptyList(),
    comparisonRequest = request,
    answerModelRequired = false
)
```

Use the exact bilingual messages from the design for `OPERANDS` and `TARGET_TYPE`; join both into one concise sentence when both are missing.

- [ ] **Step 5: Remove the nested catalog lookup from prompt construction**

Change the prompt builder contract to consume evidence:

```kotlin
private fun getSystemPrompt(
    persona: String,
    contextType: String,
    contextId: String,
    userMessage: String,
    intentDecision: AgentIntentDecision,
    labelSummary: GenerationLabelSummary,
    evidence: AgentPromptEvidence
): PromptBuildResult
```

Delete its internal `promptEvidence(...)` call. Preserve all existing safety, planning, persona, and grounding prompt rules, but derive `groundingPrompt` and returned evidence only from the supplied object.

- [ ] **Step 6: Make SSE emit local completion without opening the provider**

In `prepareStreamingMessage`, after `generateTurn`, branch before `requireNotNull(capturedProfile)`:

```kotlin
if (!generated.answerModelRequired) {
    val completed = completeGeneratedTurn(
        begin.turnId, begin.traceId, sessionId, startedAt, generated
    )
    return PreparedChatTurn.Completed(
        begin.traceId, begin.turnId, userMessage, completed
    )
}
```

In `AgentStreamingService.stream`, send `started` then `completed` for both `Replayed` and `Completed` through a shared terminal-emission helper. Only `Started` may create a provider task or be cancelled as an active provider turn.

- [ ] **Step 7: Run the focused workflow tests and confirm GREEN**

Run the command from Step 2 once. Expected: both classes pass, complete comparison stays at `0/1 + 1 + 1` remote calls, and incomplete comparison stays at `0/1 + 1 + 0`.

- [ ] **Step 8: Commit the orchestration slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamingService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt
git commit -m "feat: execute durable comparison workflow"
```

### Task 5: Bind comparison request and report to REST, SSE, replay, and history messages

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt:29-46`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt:64-101,181-190`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamingService.kt:226-242`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt:77-83,218-255,592-605,1288-1340`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/chat/controller/ChatStreamingControllerTest.kt`

**Interfaces:**
- Adds: nullable `comparisonRequest` and `catalogReport` to `ChatMessageResponse`.
- Retains: top-level `ChatTurnResponse.catalogReport`; does not add top-level comparison state.
- Uses: `TurnLifecycleService.projectMessage()` for history and reconstructed turn data for normal/replayed completions.

- [ ] **Step 1: Write failing response-contract tests**

Add assertions for:

```kotlin
@Test fun `HTTP synchronous completion binds comparison request and report to assistant message`()
@Test fun `history restores comparison request and report on the producing assistant message only`()
@Test fun `history independently degrades malformed optional comparison metadata`()
@Test fun `stream completion binds comparison state and report to assistant message`()
@Test fun `successful idempotent stream replay preserves message bound comparison state without persistence`()
@Test fun `completed SSE serializes message comparison state and retains top level report`()
```

For HTTP/SSE completed payloads, assert both `message.catalogReport` and the existing top-level `catalogReport` are equal. Assert user/ordinary assistant messages have null comparison fields and state never leaks across messages.

- [ ] **Step 2: Make the MySQL integration safety guard worktree-specific**

The existing test derives the database name but still hardcodes an older branch in its startup assertion. Preserve the host/name prints and replace the hardcoded equality with:

```kotlin
require(databaseName.startsWith("myapp_worktree_")) {
    "Refusing to start integration test with unexpected database: $databaseName"
}
```

Before running the integration test, verify output reports `AGENT_CHAT_TEST_DB_NAME=myapp_worktree_comparison_request_workflow` (or the same derived name with safe normalization) and a container-only host.

- [ ] **Step 3: Run focused API tests and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.AgentStreamingServiceTest" --tests "com.joysong.server.chat.controller.ChatStreamingControllerTest" --console=plain
.\gradlew.bat --offline --no-daemon mysqlIntegrationTest --tests "com.joysong.server.agent.AgentChatFlowIntegrationTest" --console=plain
```

Expected failure: nested messages do not expose comparison state/report. If Docker is unavailable, retain the environment failure as evidence and continue with non-container scopes; do not redirect to a shared database.

- [ ] **Step 4: Extend the message DTO without breaking legacy clients**

```kotlin
data class ChatMessageResponse(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val comparisonRequest: ComparisonRequest? = null,
    val catalogReport: AgentCatalogReportResponse? = null
)
```

Leave `ChatTurnResponse` unchanged except for consuming the enriched nested message.

- [ ] **Step 5: Map one projection into synchronous and history responses**

Use this controller mapper:

```kotlin
private fun ChatMessageEntity.toResponse(
    catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    comparisonRequest: ComparisonRequest? = null,
    catalogReport: AgentCatalogReportResponse? = null
): ChatMessageResponse
```

For synchronous completion, supply `turn.catalogItems`, `turn.comparisonRequest`, and `turn.catalogReport` to the nested message and retain `turn.catalogReport` at top level. For history, call `projectMessage(message)` once and supply all three projected fields.

- [ ] **Step 6: Map normal, local, and replayed SSE completions identically**

Update the streaming mapper:

```kotlin
private fun ChatMessageEntity.toResponse(
    catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    comparisonRequest: ComparisonRequest? = null,
    catalogReport: AgentCatalogReportResponse? = null
) = ChatMessageResponse(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    createdAt = createdAt.toString(),
    catalogItems = catalogItems,
    comparisonRequest = comparisonRequest,
    catalogReport = catalogReport
)

private fun ChatTurnResult.toResponse() = ChatTurnResponse(
    message = message.toResponse(catalogItems, comparisonRequest, catalogReport),
    catalogReport = catalogReport,
    catalogItems = catalogItems,
    intent = intent,
    queryTarget = queryTarget,
    nextAction = nextAction,
    traceId = traceId
)
```

The `started` user message continues through the default mapper, so its optional fields remain null. Do not reparse metadata in the streaming service.

- [ ] **Step 7: Run focused API tests and confirm GREEN**

Run each command from Step 3 once after its corresponding fix. Expected: unit/SSE tests pass; the container test either passes against the printed isolated DB or is reported once as an environment limitation.

- [ ] **Step 8: Commit the API slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/dto/ChatDtos.kt joysong-server/src/main/kotlin/com/joysong/server/chat/controller/ChatController.kt joysong-server/src/main/kotlin/com/joysong/server/agent/streaming/AgentStreamingService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/chat/controller/ChatStreamingControllerTest.kt
git commit -m "feat: expose message bound comparison state"
```

### Task 6: Decode and retain message-bound comparison state in Flutter

**Files:**
- Modify: `joysong-flutter/lib/features/agent/domain/agent_models.dart:101-255`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart:117-142,478-493`
- Modify: `joysong-flutter/test/features/agent/agent_chat_controller_test.dart`
- Modify: `joysong-flutter/test/features/agent/agent_stream_repository_test.dart`

**Interfaces:**
- Adds: `AgentComparisonOperand` and `AgentComparisonRequest`.
- Adds: nullable `comparisonRequest` and `catalogReport` to `ChatMessage`.
- Retains: legacy top-level `ChatTurn.catalogReport` and applies it only as a controller fallback when the message report is absent.

- [ ] **Step 1: Write failing Dart model, stream, and history tests**

Add:

```dart
test('ChatMessage decodes message-bound comparison workflow fields', () {});
test('ChatMessage keeps legacy payload compatible when comparison fields are absent', () {});
test('ChatMessage degrades malformed comparison request and report independently', () {});
test('ChatTurn keeps legacy top-level catalog report', () {});
test('completed SSE decodes message-bound comparison request and report', () {});
test('completed event prefers message-bound report over legacy top-level report', () {});
test('completed event falls back to legacy top-level report', () {});
test('openSession restores message-bound comparison request and report', () {});
```

- [ ] **Step 2: Run focused Flutter data tests and confirm RED**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_stream_repository_test.dart test/features/agent/agent_chat_controller_test.dart
```

Expected failure: `ChatMessage` has no comparison fields and the controller only merges catalog links.

- [ ] **Step 3: Add immutable comparison models and safe optional decoders**

Add:

```dart
class AgentComparisonOperand {
  const AgentComparisonOperand({
    required this.entityType,
    required this.entityId,
    required this.displayName,
  });
  final String entityType;
  final String entityId;
  final String displayName;
  factory AgentComparisonOperand.fromJson(Object? json);
}

class AgentComparisonRequest {
  const AgentComparisonRequest({
    required this.operands,
    required this.targetType,
    required this.dimensions,
    required this.constraints,
    required this.missingFields,
  });
  final List<AgentComparisonOperand> operands;
  final String? targetType;
  final List<String> dimensions;
  final Map<String, String> constraints;
  final Set<String> missingFields;
  bool get isComplete => missingFields.isEmpty;
  factory AgentComparisonRequest.fromJson(Object? json);
}
```

Use private helpers that wrap each optional object in its own `try/catch` and return null on malformed data. Extend `ChatMessage` constructor, fields, `copyWith`, and `fromJson` with nullable `comparisonRequest` and `catalogReport`. Keep unknown/missing fields backward compatible.

- [ ] **Step 4: Attach completed-turn data to the specific assistant message**

Replace the current completion merge with:

```dart
final assistantMessage = turn.message.copyWith(
  catalogItems: turn.message.catalogItems.isNotEmpty
      ? turn.message.catalogItems
      : turn.catalogItems,
  catalogReport: turn.message.catalogReport ?? turn.catalogReport,
);
```

`comparisonRequest` comes only from `turn.message`; do not invent a top-level fallback. The existing history path already retains parsed messages, so verify it without adding another controller state object or request.

- [ ] **Step 5: Run focused Flutter data tests and confirm GREEN**

Run the command from Step 2 once. Expected: model, SSE, fallback, and history tests pass.

- [ ] **Step 6: Commit the Flutter data slice**

```powershell
git add -- joysong-flutter/lib/features/agent/domain/agent_models.dart joysong-flutter/lib/features/agent/presentation/agent_chat_controller.dart joysong-flutter/test/features/agent/agent_chat_controller_test.dart joysong-flutter/test/features/agent/agent_stream_repository_test.dart
git commit -m "feat: retain comparison state on chat messages"
```

### Task 7: Restore the message-bound Flutter comparison status and horizontal report

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart:202-377`
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart:191-213`
- Modify: `joysong-flutter/test/features/agent/agent_chat_page_test.dart:93-165`
- Create: `joysong-flutter/test/features/agent/agent_catalog_cards_test.dart`

**Interfaces:**
- Adds: read-only `AgentComparisonStatusCard(request: ...)`.
- Restores: `AgentCatalogReportCard` beneath its producing assistant message.
- Preserves: `AgentCatalogLinkList` only for messages without comparison state.

- [ ] **Step 1: Write failing page precedence and card tests**

Page tests:

```dart
testWidgets('incomplete comparison shows status card and suppresses catalog links', (tester) async {});
testWidgets('complete comparison shows message-bound report and suppresses duplicate links', (tester) async {});
testWidgets('ordinary catalog message still shows lightweight links', (tester) async {});
```

Focused card tests:

```dart
testWidgets('status card renders operand and target guidance in Chinese', (tester) async {});
testWidgets('status card renders operand and target guidance in English', (tester) async {});
testWidgets('institution and doctor tables omit price rows', (tester) async {});
testWidgets('project table shows category reference price rating and tags', (tester) async {});
testWidgets('institution project table shows city category prices rating reviews sales verification and tags', (tester) async {});
testWidgets('doctor table keeps one column per doctor and shows multiple practice institutions and verification summary', (tester) async {});
testWidgets('comparison table localizes missing structured values', (tester) async {});
testWidgets('comparison table renders at most four operand columns and remains horizontally scrollable', (tester) async {});
```

- [ ] **Step 2: Run focused widget tests and confirm RED**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_catalog_cards_test.dart test/features/agent/agent_chat_page_test.dart
```

Expected failure: the page only renders link cards, no status widget exists, and existing report tests expect no table.

- [ ] **Step 3: Add the bilingual incomplete-state card**

Add:

```dart
class AgentComparisonStatusCard extends StatelessWidget {
  const AgentComparisonStatusCard({required this.request, super.key});
  final AgentComparisonRequest request;

  @override
  Widget build(BuildContext context) {
    final isZh = Localizations.localeOf(context).languageCode == 'zh';
    final guidance = request.missingFields.map((field) => switch (field) {
      'OPERANDS' => isZh
          ? '请选择至少两个对比对象'
          : 'Select at least two items to compare',
      'TARGET_TYPE' => isZh
          ? '请明确要比较机构、医生、项目还是机构项目'
          : 'Specify whether to compare clinics, doctors, treatments, or clinic treatments',
      _ => null,
    }).whereType<String>().toList();
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(isZh ? '还需要补充对比信息' : 'More comparison details needed'),
            if (request.operands.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(request.operands.map((item) => item.displayName).join(' · ')),
            ],
            for (final text in guidance) ...[
              const SizedBox(height: 8),
              Text(text),
            ],
          ],
        ),
      ),
    );
  }
}
```

The card is read-only: no buttons, editors, dimension picker, or add/remove/reorder actions.

- [ ] **Step 4: Render exactly one attachment mode per assistant message**

Inside the existing message loop, use this precedence:

```dart
final request = message.comparisonRequest;
if (request != null && !request.isComplete) {
  attachments.add(AgentComparisonStatusCard(request: request));
} else if (request != null && request.isComplete && message.catalogReport != null) {
  attachments.add(AgentCatalogReportCard(
    report: message.catalogReport!,
    onOpen: widget.onOpenCatalogItem,
    canOpen: _canOpenCatalogItem,
  ));
} else if (request == null && message.catalogItems.isNotEmpty) {
  attachments.add(AgentCatalogLinkList(
    items: message.catalogItems,
    onOpen: widget.onOpenCatalogItem,
    canOpen: _canOpenCatalogItem,
  ));
}
```

A complete request without a report renders neither unrelated links nor a fabricated table.

- [ ] **Step 5: Bound and localize the horizontal table**

In `AgentComparisonTable`, use:

```dart
final visibleItems = report.items.take(4).toList(growable: false);
final missingValue = Localizations.localeOf(context).languageCode == 'zh'
    ? '暂无平台数据'
    : 'Not available';
```

Retain horizontal `SingleChildScrollView`; use backend `comparisonDimensions` strings as exact attribute keys and fall back to unioned structured attribute keys only when dimensions are absent. Render null/blank cells with `missingValue`. Do not reproduce target/dimension projection logic in Flutter.

- [ ] **Step 6: Run focused widget tests and confirm GREEN**

Run the command from Step 2 once. Expected: status, precedence, target rows, doctor multi-institution, missing-value, four-column, and scrolling tests pass.

- [ ] **Step 7: Commit the Flutter presentation slice**

```powershell
git add -- joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart joysong-flutter/test/features/agent/agent_chat_page_test.dart joysong-flutter/test/features/agent/agent_catalog_cards_test.dart
git commit -m "feat: show message bound comparison reports"
```

### Task 8: Update the workflow UML and perform final verification

**Files:**
- Modify: `design/AI_AGENT_SEQUENCE.puml:25-111`
- Verify: all files changed in Tasks 1-7

**Interfaces:**
- Documents: comparison construction, completeness branch, exact evidence filtering, persistence/replay, and message-bound Flutter rendering.
- Verifies: backend, API/SSE, Flutter, formatting, and clean worktree state.

- [ ] **Step 1: Update the sequence diagram**

Add the comparison-specific sequence after final routing:

```plantuml
alt final intent == COMPARISON
  ChatService -> TurnLifecycleService: restore latest message ComparisonRequest
  ChatService -> AgentCatalogService: promptEvidence(..., COMPARISON) once
  ChatService -> ComparisonRequestBuilder: build + normalize in memory
  alt request incomplete
    ChatService -> TurnLifecycleService: persist deterministic clarification + request
    note right of ChatService: no answer-model call
  else request complete
    ChatService -> AgentCatalogService: filter exact operand evidence in memory
    ChatService -> LLM: one grounded answer request
    ChatService -> TurnLifecycleService: persist request + report with assistant message
  end
end
TurnLifecycleService --> ChatController: message-bound request + report
ChatController --> Flutter: REST/SSE/history/replay
Flutter -> Flutter: render status OR report OR ordinary links
```

Keep existing safety and planning precedence visible and label filtering as in-memory rather than a second catalog query.

- [ ] **Step 2: Run Flutter verification once**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat analyze
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent
```

If the broad feature test exceeds ten minutes, stop it and report the last completed tests and slowest visible scope.

- [ ] **Step 3: Run one backend non-container suite if targeted tests are green**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --console=plain
```

Run this broader suite at most once. Stop after ten minutes and report progress rather than retrying.

- [ ] **Step 4: Check formatting, accidental artifacts, and acceptance criteria**

```powershell
git diff --check
git status --short
```

Confirm there are no project-local Gradle caches, Flutter-generated registrant changes, temporary files, duplicated projection helpers, or untracked test artifacts. Verify each of the twelve acceptance criteria in the design against tests or fresh command output.

- [ ] **Step 5: Commit documentation and any final test-only adjustment**

```powershell
git add -- design/AI_AGENT_SEQUENCE.puml
git commit -m "docs: update comparison agent workflow"
```

The implementation is ready for review only after fresh verification output supports every completion claim.
