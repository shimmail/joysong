# AI Agent Human Consultation Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize a user's request for a real-person consultation, deterministically return up to four currently consultable institutions, let the user choose an institution and a current consultant, and open the existing one-to-one DM flow without invoking the answer model.

**Architecture:** Extend the existing intent router with `HUMAN_CONSULTATION` and `SELECT_INSTITUTION`; reuse the current optional intent-model pass only for ambiguous text. A set-based consultant-eligibility query and a bounded catalog selector build institution cards. `ChatService` completes this route locally through the existing turn lifecycle, so REST, SSE, metadata, history, and idempotent replay keep their current envelopes. Flutter resolves only safe institution resource IDs, reloads current consultants through the existing endpoint, and sends only the returned consultant user ID to the existing DM creation path.

**Tech Stack:** Kotlin 1.9, Spring Boot, JdbcTemplate, Spring Data JPA, Jackson, MockK, JUnit 5, Testcontainers MySQL, Dart, Flutter widget tests, PlantUML.

## Global Constraints

- Work only in `D:\code\kotlin\joysong\.worktrees\agent-human-consultation` on branch `codex/agent-human-consultation`.
- Reuse the current REST/SSE DTOs, message metadata JSON, consultant endpoint, picker, and DM APIs. Add no table, column, migration, endpoint, tool call, route, or state-management layer.
- `SAFETY_SCREENING` remains the highest-priority intent. A safety turn never emits commercial institution cards.
- Clear current-message intent and negation remain authoritative over history and model output. A prior human handoff may be reused only when the current message itself has human-consultation evidence or an unresolved reference.
- `HUMAN_CONSULTATION` always fixes `queryTarget=INSTITUTION`; neither local code nor the model may choose a consultant or messaging user ID.
- The handoff branch may call the intent model once only when routing is ambiguous. It must call the answer model zero times and must not send the raw handoff phrase through ordinary catalog search.
- An institution is selectable only when it is verified, not deleted, and has at least one approved, non-revoked consultant membership whose user and institution are not deleted and whose nickname is non-blank.
- Resolve consultant eligibility with one set-based query. Never call `listApprovedConsultants` once per institution.
- Rank without duplicates: exact current institution, explicit current city, eligible detail-context institution, saved profile city when no explicit city exists, then nationwide. Within each tier use rating descending and institution ID ascending; return at most four.
- Persist institution resource IDs only. Consultant user IDs are loaded after a tap and are never written to Agent message metadata or history.
- Flutter may map `INSTITUTION` to explicit `institutionId` or its own `id`, and `INSTITUTION_PROJECT` only to explicit `institutionId`. `DOCTOR`, `PROJECT`, unknown types, and blank IDs never become consultation or DM targets.
- Follow repository test discipline: smallest relevant test first, rerun only a failed scope, then run at most one backend full suite. Stop a full suite after ten minutes and report progress; do not repeat a passing command.
- Any MySQL integration test must use the existing `myapp_worktree_*` Testcontainers database guard and print the resolved host and database name before schema work. Do not connect to or reset a shared database.
- Use the cached runtimes: `C:\Users\shimeng\.gradle` with Gradle offline mode and `D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat`. Do not retry the previously timed-out Gradle distribution download.
- Do not modify unrelated dirty files in the main worktree. Do not change institution-membership semantics or the institution relationship diagram.

---

## File Structure

### Backend production

- Modify `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt`: new intent/action, bilingual local signals, precedence, negation, context inheritance, fixed target/action.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt`: shared consultant-eligibility SQL and one set-based institution-ID query.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverSearchService.kt`: expose its existing narrow named-institution phrase detector without invoking general search.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt`: consultable-institution selection, stable ranking, profile fallback, institution card mapping.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt`: intent-model whitelist/validation and deterministic handoff short circuit.
- Modify `joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt`: summary allowlists and topic validation.
- Leave `TurnLifecycleService`, REST/SSE DTOs, `AgentStreamingService`, entities, repositories, and migrations unchanged unless a failing test proves a compatibility defect.

### Backend tests

- Modify `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionConsultantServiceTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverSearchServiceTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/chat/service/ChatServiceContextRoutingTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt`.
- Modify `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`.

### Flutter production and tests

- Modify `joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart`: safe institution-ID resolver and action rendering in ordinary/report paths.
- Modify `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart`: wire the existing callback into both card paths.
- Modify `joysong-flutter/lib/features/shell/presentation/app_shell.dart`: pass both Agent entry points into the existing institution consultant picker.
- Modify `joysong-flutter/test/features/agent/agent_catalog_cards_test.dart`.
- Modify `joysong-flutter/test/features/agent/agent_chat_page_test.dart`.
- Modify `joysong-flutter/test/features/shell/app_shell_navigation_test.dart` only for the two Agent entry-point assertions; keep existing picker tests unchanged.

### Documentation and generated design artifact

- Modify `docs/AI_AGENT_DEVELOPMENT.md`.
- Modify `docs/AI_AGENT_TESTING.md` only to correct the stale SSE statement and record the new focused contract/commands.
- Modify `design/AI_AGENT_SEQUENCE.puml`.
- Regenerate `design/AI_AGENT_SEQUENCE.png` from that source and visually inspect it.

---

### Task 1: Add the human-consultation routing contract

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt:5-16,102-228,231-445,545-650,772-829`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt`

**Interfaces:**
- Adds `AgentIntent.HUMAN_CONSULTATION`.
- Adds `AgentNextAction.SELECT_INSTITUTION`.
- Guarantees `HUMAN_CONSULTATION/INSTITUTION/SELECT_INSTITUTION/searchCatalog=false` before candidate availability is known.

- [ ] **Step 1: Write failing local-routing, negation, priority, and history tests**

Add focused tests with these exact behaviors:

```kotlin
@Test
fun `clear Chinese and English consultation requests use the local human route`() {
    listOf(
        "我想找真人咨询",
        "帮我找咨询师",
        "Connect me to a human consultant",
        "I want to speak to a specialist"
    ).forEach { query ->
        val result = router.assessCurrent(query, "GENERAL")

        assertEquals(AgentIntent.HUMAN_CONSULTATION, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertEquals(AgentNextAction.SELECT_INSTITUTION, result.decision.nextAction)
        assertFalse(result.decision.searchCatalog)
        assertFalse(result.requiresLlmParsing)
    }
}

@Test
fun `negated consultation does not trigger or inherit the human route`() {
    listOf("不需要真人咨询", "Do not connect me to a person").forEach { query ->
        val result = router.supplementWithContext(
            router.assessCurrent(query, "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.HUMAN_CONSULTATION, null))
        )

        assertEquals(
            AgentLabelPolarity.NEGATIVE,
            result.intentEvidence.single { it.intent == AgentIntent.HUMAN_CONSULTATION }.polarity
        )
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
    }
}

@Test
fun `safety remains primary over a request for a person`() {
    val result = router.assessCurrent("我怀孕了，请帮我转真人咨询", "GENERAL")

    assertEquals(AgentIntent.SAFETY_SCREENING, result.decision.intent)
    assertEquals(null, result.decision.queryTarget)
    assertEquals(AgentNextAction.COMPLETE_SAFETY_SCREENING, result.decision.nextAction)
}

@Test
fun `unrelated follow up does not inherit prior human consultation`() {
    val result = router.supplementWithContext(
        router.assessCurrent("今天天气不错", "GENERAL"),
        listOf(router.validatedDecision(AgentIntent.HUMAN_CONSULTATION, null))
    )

    assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
}

@Test
fun `unresolved reference may retain prior human consultation`() {
    val result = router.supplementWithContext(
        router.assessCurrent("那家呢", "GENERAL"),
        listOf(router.validatedDecision(AgentIntent.HUMAN_CONSULTATION, null))
    )

    assertEquals(AgentIntent.HUMAN_CONSULTATION, result.decision.intent)
    assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
}
```

- [ ] **Step 2: Run the router test and confirm RED**

From `joysong-server`:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.service.AgentIntentRouterTest" --console=plain
```

Expected failure: the new enum values and route evidence do not exist.

- [ ] **Step 3: Add the enum values and fixed decision contract**

Add the values, then normalize the target inside `validatedDecision` so a model-provided doctor/project target can never survive:

```kotlin
enum class AgentIntent {
    GENERAL_CHAT,
    CATALOG_QA,
    COMPARISON,
    PLANNING,
    DETAIL_SUMMARY,
    HUMAN_CONSULTATION,
    SAFETY_SCREENING
}

enum class AgentNextAction {
    NONE,
    SHOW_CATALOG,
    START_PLANNING,
    SELECT_INSTITUTION,
    COMPLETE_SAFETY_SCREENING
}
```

At the beginning of `validatedDecision` use:

```kotlin
val effectiveTarget = if (intent == AgentIntent.HUMAN_CONSULTATION) {
    AgentQueryTarget.INSTITUTION
} else {
    queryTarget
}
```

Build the decision with `effectiveTarget`, keep HUMAN out of ordinary `searchCatalog`, and extend the action `when`:

```kotlin
AgentIntent.HUMAN_CONSULTATION -> AgentNextAction.SELECT_INSTITUTION
```

- [ ] **Step 4: Add high-precision bilingual evidence and preserve safety priority**

Use a bounded phrase list; do not add broad tokens such as `human`, `person`, or `专家` alone:

```kotlin
val humanConsultationTerms = listOf(
    "真人咨询", "人工咨询", "真人客服", "人工客服",
    "转人工", "转接真人", "转接咨询师", "联系咨询师", "找咨询师",
    "找真人", "真人顾问", "人工服务",
    "human consultation", "human consultant", "human advisor",
    "real person", "live agent", "manual service",
    "speak to a person", "talk to a person",
    "speak to a specialist", "talk to a specialist",
    "connect me to a person", "transfer me to a consultant"
)
```

Add `HUMAN_CONSULTATION` to `specificIntentEvidence`, add the terms to `intentActionTerms` and `routingTerms`, and classify them as `SignalFamily.BUSINESS_ACTION`. Resolve positive intents in this order:

```kotlin
val intent = when {
    AgentIntent.SAFETY_SCREENING in positiveIntents -> AgentIntent.SAFETY_SCREENING
    AgentIntent.HUMAN_CONSULTATION in positiveIntents -> AgentIntent.HUMAN_CONSULTATION
    detailSummaryIsPrimary -> AgentIntent.DETAIL_SUMMARY
    preferredIntent in positiveIntents -> preferredIntent!!
    AgentIntent.COMPARISON in positiveIntents -> AgentIntent.COMPARISON
    AgentIntent.PLANNING in positiveIntents -> AgentIntent.PLANNING
    AgentIntent.CATALOG_QA in positiveIntents -> AgentIntent.CATALOG_QA
    else -> AgentIntent.GENERAL_CHAT
}
```

- [ ] **Step 5: Bound history inheritance**

Before filtering `usableContexts`, calculate:

```kotlin
val currentAllowsHumanContext = current.intentEvidence.any {
    it.intent == AgentIntent.HUMAN_CONSULTATION &&
        it.polarity != AgentLabelPolarity.NEGATIVE
} || "UNRESOLVED_CURRENT_REFERENCE" in current.ambiguityReasons
```

Add this condition to the existing context filter:

```kotlin
(candidate.intent != AgentIntent.HUMAN_CONSULTATION || currentAllowsHumanContext)
```

Keep all existing safety and target-conflict checks intact.

- [ ] **Step 6: Run the focused router test and confirm GREEN**

Run the Step 2 command once. Expected: all existing and new router cases pass.

- [ ] **Step 7: Commit the route slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentIntentRouter.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentIntentRouterTest.kt
git commit -m "feat: route human consultation requests"
```

---

### Task 2: Select currently consultable institutions deterministically

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt:12-39`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverSearchService.kt:282-307`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt:1-50,245-260`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionConsultantServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverSearchServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt`
- Modify fixture construction in: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt:1994-2003`

**Interfaces:**
- Adds `InstitutionConsultantService.listConsultableInstitutionIds(): Set<String>`.
- Adds `ConsultableInstitutionSelection` and `AgentCatalogService.selectConsultableInstitutions(userId, query, contextInstitutionId)`.
- Reuses the same consultant-membership predicate as the existing picker query.

- [ ] **Step 1: Write failing eligibility-query tests**

Add a test that captures both SQL strings and proves one set query uses every picker predicate:

```kotlin
@Test
fun `consultable institution ids use one set query with picker eligibility`() {
    val jdbcTemplate = mockk<JdbcTemplate>()
    val service = InstitutionConsultantService(jdbcTemplate)
    val idsSql = slot<String>()
    val pickerSql = slot<String>()

    every { jdbcTemplate.queryForList(capture(idsSql), String::class.java) } returns
        listOf("institution-2", "institution-1")
    every {
        jdbcTemplate.query(
            capture(pickerSql),
            any<RowMapper<InstitutionConsultant>>(),
            "institution-1"
        )
    } returns emptyList()

    assertEquals(setOf("institution-2", "institution-1"), service.listConsultableInstitutionIds())
    service.listApprovedConsultants("institution-1")

    listOf(
        "im.member_role = 'CONSULTANT'",
        "im.status = 'APPROVED'",
        "im.revoked_at IS NULL",
        "u.deleted_at IS NULL",
        "i.deleted_at IS NULL",
        "u.nickname IS NOT NULL",
        "TRIM(u.nickname) <> ''"
    ).forEach { predicate ->
        assertTrue(idsSql.captured.contains(predicate))
        assertTrue(pickerSql.captured.contains(predicate))
    }
    assertTrue(idsSql.captured.contains("i.is_verified = TRUE"))
    verify(exactly = 1) { jdbcTemplate.queryForList(any<String>(), String::class.java) }
}
```

- [ ] **Step 2: Write failing ranking and fallback tests**

In `AgentCatalogServiceTest`, add independent cases named:

- `consultable institutions accumulate exact city profile and nationwide tiers stably`
- `consultable institutions exclude unverified deleted and unavailable rows`
- `profile lookup failure falls back nationwide`
- `consultable institutions use rating then id and stop at four`
- `unavailable named institution returns eligible alternatives`
- `eligible detail context precedes profile only when current text has no institution or city`
- `empty consultable institution set returns an empty selection`

In `DiscoverSearchServiceTest`, add `named institution phrase detection excludes generic institution requests`: specific values such as `星颜医疗美容医院` and `Aurora clinic` return true, while `推荐医美机构`, `find a clinic`, and a plain human-handoff phrase return false.

The primary ranking assertion must use deliberately conflicting ratings:

```kotlin
assertEquals(
    listOf("explicit-low", "shanghai-high", "shanghai-low", "national-high"),
    result.items.map { it.id }
)
assertTrue(result.items.all {
    it.type == "INSTITUTION" &&
        it.institutionId == it.id &&
        it.canChatWithHuman
})
verify(exactly = 1) { institutionConsultantService.listConsultableInstitutionIds() }
verify(exactly = 0) { institutionConsultantService.listApprovedConsultants(any()) }
```

- [ ] **Step 3: Run both focused test classes and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.identity.service.InstitutionConsultantServiceTest" --tests "com.joysong.server.discover.service.DiscoverSearchServiceTest" --tests "com.joysong.server.agent.service.AgentCatalogServiceTest" --console=plain
```

Expected failure: the set query, narrow named-institution detector, and selection contract do not exist.

- [ ] **Step 4: Share the SQL eligibility definition**

In `InstitutionConsultantService`, define one fragment used by both queries:

```kotlin
private val eligibleConsultantFromWhere = """
    FROM institution_memberships im
    JOIN users u ON u.id = im.user_id
    JOIN institutions i ON i.id = im.institution_id
    WHERE im.member_role = 'CONSULTANT'
      AND im.status = 'APPROVED'
      AND im.revoked_at IS NULL
      AND u.deleted_at IS NULL
      AND i.deleted_at IS NULL
      AND u.nickname IS NOT NULL
      AND TRIM(u.nickname) <> ''
""".trimIndent()
```

Build `listApprovedConsultants` from that fragment plus `AND im.institution_id = ?`, and add:

```kotlin
fun listConsultableInstitutionIds(): Set<String> =
    jdbcTemplate.queryForList(
        """
        SELECT DISTINCT im.institution_id
        $eligibleConsultantFromWhere
          AND i.is_verified = TRUE
        ORDER BY im.institution_id
        """.trimIndent(),
        String::class.java
    ).toSet()
```

- [ ] **Step 5: Add the bounded catalog selection contract**

First extract the existing pattern check in `DiscoverSearchService` into a public, side-effect-free helper and reuse it from `explicitlyRequestedEntityTypes`:

```kotlin
fun hasNamedInstitutionPhrase(query: String): Boolean =
    namedInstitutionPattern.findAll(query).any { match ->
        val candidate = match.groupValues[1].trim().lowercase()
        candidate !in genericInstitutionPrefixes &&
            genericInstitutionPhrases.none(candidate::contains) &&
            explicitTreatmentTerms.none(candidate::contains)
    }
```

Add constructor dependencies for `InstitutionConsultantService` and `AgentProfileService`. Update all three manual construction sites—`AgentCatalogServiceTest.kt:47`, `AgentCatalogServiceTest.kt:374`, and `AgentWorkflowCoreTest.kt:1994`—then add:

```kotlin
data class ConsultableInstitutionSelection(
    val items: List<AgentCatalogItemResponse>,
    val requestedInstitutionUnavailable: Boolean
)
```

Implement the selector without calling general catalog search:

```kotlin
fun selectConsultableInstitutions(
    userId: String,
    query: String,
    contextInstitutionId: String? = null
): ConsultableInstitutionSelection {
    val allInstitutions = institutionRepository.findAll()
    val consultableIds = institutionConsultantService.listConsultableInstitutionIds()
    val ranked = allInstitutions
        .filter { it.id in consultableIds && it.isVerified && it.deletedAt == null }
        .sortedWith(compareByDescending<InstitutionEntity> { it.rating }.thenBy { it.id })
    val explicitlyNamed = allInstitutions.filter {
        it.name.isNotBlank() && query.contains(it.name, ignoreCase = true)
    }
    val explicitCities = discoverSearchService.citiesMentionedIn(query)
    val profileCity = if (explicitCities.isEmpty()) {
        runCatching { agentProfileService.get(userId).city.trim().takeIf(String::isNotBlank) }
            .getOrNull()
    } else {
        null
    }
    val selected = linkedMapOf<String, InstitutionEntity>()

    fun addTier(values: Iterable<InstitutionEntity>) {
        values.forEach { candidate ->
            if (selected.size < 4) selected.putIfAbsent(candidate.id, candidate)
        }
    }

    addTier(ranked.filter { candidate -> explicitlyNamed.any { it.id == candidate.id } })
    if (explicitlyNamed.isEmpty() && explicitCities.isEmpty()) {
        addTier(ranked.filter { it.id == contextInstitutionId })
    }
    addTier(ranked.filter { candidate ->
        explicitCities.any { it.equals(candidate.city, ignoreCase = true) }
    })
    if (explicitCities.isEmpty() && profileCity != null) {
        addTier(ranked.filter { it.city.equals(profileCity, ignoreCase = true) })
    }
    addTier(ranked)

    val requestedInstitutionUnavailable =
        (explicitlyNamed.isEmpty() && discoverSearchService.hasNamedInstitutionPhrase(query)) ||
            explicitlyNamed.any { it.id !in consultableIds || !it.isVerified || it.deletedAt != null }
    return ConsultableInstitutionSelection(
        items = selected.values.map { institutionCatalogItem(it, includeSummary = false) },
        requestedInstitutionUnavailable = requestedInstitutionUnavailable
    )
}
```

Extract the existing institution-card construction and call it from both existing report construction and this selector:

```kotlin
private fun institutionCatalogItem(
    institution: InstitutionEntity,
    includeSummary: Boolean
): AgentCatalogItemResponse = AgentCatalogItemResponse(
    type = "INSTITUTION",
    id = institution.id,
    name = institution.name,
    subtitle = institution.city,
    summary = institution.description.takeIf { includeSummary }.orEmpty(),
    attributes = linkedMapOf(
        AgentText.value("城市", "City") to institution.city,
        AgentText.value("机构认证", "Clinic verified") to AgentText.value(
            if (institution.isVerified) "已认证" else "未认证",
            if (institution.isVerified) "Verified" else "Not verified"
        ),
        AgentText.value("评分", "Rating") to institution.rating.toPlainString(),
        AgentText.value("评价数", "Review count") to institution.reviewCount.toString(),
        AgentText.value("医生数", "Doctors") to institution.doctorCount.toString(),
        AgentText.value("擅长领域", "Specialties") to institution.specialties
    ).filterValues(String::isNotBlank),
    institutionId = institution.id,
    canChatWithHuman = true
)
```

The existing report path calls it with `includeSummary = mode != "COMPARISON"`; the consultation path calls it with `false`.

- [ ] **Step 6: Run the three focused test classes and confirm GREEN**

Run the Step 3 command once. Expected: all three classes pass and MockK verifies one eligibility-ID query with no per-institution picker calls.

- [ ] **Step 7: Commit the candidate-selection slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/identity/service/InstitutionConsultantService.kt joysong-server/src/main/kotlin/com/joysong/server/discover/service/DiscoverSearchService.kt joysong-server/src/main/kotlin/com/joysong/server/agent/service/AgentCatalogService.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/InstitutionConsultantServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/discover/service/DiscoverSearchServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/service/AgentCatalogServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: select consultable institutions"
```

---

### Task 3: Complete human handoff turns without the answer model

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt:177-203,558-730,869-1005,1300-1317`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`

**Interfaces:**
- Final route short-circuits before `contextualSearchQuery`, `promptEvidence`, `generationProfile`, or the answer-model caller.
- Uses fixed localized text and the existing `GeneratedTurn(answerModelRequired=false)` completion path.
- Empty candidates change only this turn's action from `SELECT_INSTITUTION` to `NONE`.

- [ ] **Step 1: Write failing deterministic workflow tests**

Add focused cases named:

- `clear human consultation persists fixed localized response without either model`
- `ambiguous consultation uses the intent model and skips the answer model`
- `human parser route rejects a doctor target`
- `empty human consultation candidates persist NONE and no cards`
- `unavailable named institution returns alternatives with SELECT_INSTITUTION`

For the local success case, capture `CompleteTurnCommand` and assert:

```kotlin
assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
assertEquals("INSTITUTION", completed.captured.queryTarget)
assertEquals("SELECT_INSTITUTION", completed.captured.nextAction)
assertEquals(institutionItem, completed.captured.catalogItems.single())
assertEquals("", completed.captured.modelName)
verify(exactly = 0) {
    catalog.contextualSearchQuery(any(), any())
    catalog.promptEvidence(any(), any(), any(), any(), any(), any())
}
```

Set and restore `LocaleContextHolder` in `try/finally` when asserting the exact Chinese and English messages.

For the ambiguous case use `Could someone help me with this?`, let only the intent server return:

```json
{"intent":"HUMAN_CONSULTATION","queryTarget":null,"keywords":[]}
```

Stub `catalog.hasInstitutionProjectMatch("Could someone help me with this?")` to return `false`. Create no completion-server expectation; successful `verify()` proves the answer model was not called.

- [ ] **Step 2: Run the workflow class and confirm RED**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.AgentWorkflowCoreTest" --console=plain
```

Expected failure: no deterministic human branch exists and the parser whitelist lacks the new intent.

- [ ] **Step 3: Short-circuit immediately after the final route decision**

Insert this directly after `mergeParsedRoute` and before contextual query/catalog/profile generation work:

```kotlin
val intentDecision = agentIntentRouter.mergeParsedRoute(routeAssessment, parsedRoute)
if (intentDecision.intent == AgentIntent.HUMAN_CONSULTATION) {
    return generateHumanConsultationTurn(session, content, intentDecision)
}
```

Resolve only a safe detail-context institution hint:

```kotlin
private fun consultationContextInstitutionId(session: ChatSessionEntity): String? =
    when (session.contextType.trim().uppercase()) {
        "INSTITUTION" -> session.contextId.trim().takeIf(String::isNotBlank)
        "INSTITUTION_PROJECT" -> institutionProjectRepository.findById(session.contextId)
            .orElse(null)
            ?.institutionId
        else -> null
    }
```

Implement the deterministic turn:

```kotlin
private fun generateHumanConsultationTurn(
    session: ChatSessionEntity,
    content: String,
    decision: AgentIntentDecision
): GeneratedTurn {
    val selection = agentCatalogService.selectConsultableInstitutions(
        userId = session.userId,
        query = content,
        contextInstitutionId = consultationContextInstitutionId(session)
    )
    val items = selection.items.filter {
        it.type.equals("INSTITUTION", ignoreCase = true) &&
            it.id.isNotBlank() &&
            !it.institutionId.isNullOrBlank() &&
            it.canChatWithHuman
    }.take(4)
    val resolvedDecision = decision.copy(
        queryTarget = AgentQueryTarget.INSTITUTION,
        searchCatalog = false,
        nextAction = if (items.isEmpty()) AgentNextAction.NONE else AgentNextAction.SELECT_INSTITUTION
    )
    val response = when {
        items.isEmpty() -> AgentText.value(
            "目前没有可转接真人咨询的机构，请稍后再试。",
            "No institutions are currently available for a human-consultation handoff. Please try again later."
        )
        selection.requestedInstitutionUnavailable -> AgentText.value(
            "你提到的机构目前无法提供真人转接。你可以选择下方其他机构，查看其当前可联系的咨询师。",
            "The institution you mentioned cannot currently provide a handoff. Choose another institution below to see its currently available consultants."
        )
        else -> AgentText.value(
            "我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。",
            "I can help connect you with a real consultant. Choose an institution to see its currently available consultants."
        )
    }
    return GeneratedTurn(
        content = response,
        intentDecision = resolvedDecision,
        llmResult = LlmCallResult("", fallbackUsed = false),
        catalogReport = null,
        catalogItems = items,
        answerModelRequired = false
    )
}
```

Import `AgentNextAction`. Let candidate-query failures propagate into the existing failed-turn contract; do not convert them into a successful empty result.

- [ ] **Step 4: Extend and strictly validate the intent-model contract**

Change the parser schema string to include `HUMAN_CONSULTATION`, then add these instructions:

```text
Use HUMAN_CONSULTATION only when the user asks to speak with or transfer to a real person or consultant.
For HUMAN_CONSULTATION, queryTarget must be INSTITUTION or null.
Never return a consultant user ID or invent an institution.
```

In `isCompatibleParsedRoute`, reject incompatible targets before locked-field merging:

```kotlin
if (
    AgentIntent.HUMAN_CONSULTATION in parsedIntents &&
    target != null &&
    target != AgentQueryTarget.INSTITUTION
) return false
```

Model keywords are never read by the short-circuited human branch.

- [ ] **Step 5: Complete exhaustive intent handling**

In `summaryContextDecision`, accept only the fixed target:

```kotlin
AgentIntent.HUMAN_CONSULTATION -> target == AgentQueryTarget.INSTITUTION
```

In `generationProfile`, add an unreachable defensive branch so the `when` remains exhaustive:

```kotlin
AgentIntent.HUMAN_CONSULTATION -> GenerationProfile(
    historyMessageLimit = 0,
    maxOutputTokens = 0
)
```

- [ ] **Step 6: Run the workflow class and confirm GREEN**

Run the Step 2 command once. Expected: deterministic text, empty-state action, parser validation, and zero answer-model calls pass with the existing workflow tests.

- [ ] **Step 7: Commit the deterministic turn slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/chat/service/ChatService.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt
git commit -m "feat: complete human handoff deterministically"
```

---

### Task 4: Preserve the handoff through summaries, REST, SSE, history, and replay

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt:195-208`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/chat/service/ChatServiceContextRoutingTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt`

**Compatibility:**
- No production changes are expected in `TurnLifecycleService`, `AgentStreamingService`, controllers, or DTOs.
- Actionable summary topic: `HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION`.
- Empty-result summary topic: `HUMAN_CONSULTATION:INSTITUTION`.

- [ ] **Step 1: Write failing summary validation and reconstruction tests**

Add:

```kotlin
@Test
fun `human consultation summary restores only an institution target`() {
    val valid = summaryContextDecision(
        "HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION",
        router
    )
    val empty = summaryContextDecision("HUMAN_CONSULTATION:INSTITUTION", router)
    val invalid = summaryContextDecision(
        "HUMAN_CONSULTATION:DOCTOR:SELECT_INSTITUTION",
        router
    )

    assertEquals(AgentIntent.HUMAN_CONSULTATION, valid?.intent)
    assertEquals(AgentNextAction.SELECT_INSTITUTION, valid?.nextAction)
    assertEquals(AgentNextAction.SELECT_INSTITUTION, empty?.nextAction)
    assertNull(invalid)
}
```

In the context-builder tests inside `AgentWorkflowCoreTest`, assert valid human topics survive sanitization while `HUMAN_CONSULTATION:DOCTOR:SELECT_INSTITUTION` and `HUMAN_CONSULTATION:INSTITUTION:SHOW_CATALOG` are dropped.

- [ ] **Step 2: Extend summary allowlists and topic validation**

Add the strings to the companion-object sets:

```kotlin
val validIntents = setOf(
    "GENERAL_CHAT", "CATALOG_QA", "COMPARISON", "PLANNING",
    "DETAIL_SUMMARY", "HUMAN_CONSULTATION", "SAFETY_SCREENING"
)
val validNextActions = setOf(
    "NONE", "SHOW_CATALOG", "START_PLANNING",
    "SELECT_INSTITUTION", "COMPLETE_SAFETY_SCREENING"
)
```

Extend `isValidTopic`:

```kotlin
"HUMAN_CONSULTATION" ->
    target == "INSTITUTION" && action in setOf(null, "SELECT_INSTITUTION")
```

The stored empty result has `nextAction=NONE`; the summary serializer intentionally omits `NONE`, while the reconstructed router default remains `SELECT_INSTITUTION`. The persisted turn response and replay still retain `NONE` from message metadata.

- [ ] **Step 3: Add unit coverage for metadata and idempotent snapshots**

In `AgentWorkflowCoreTest`, add cases named:

- `human consultation completion persists intent target action and institution cards`
- `idempotent replay restores the same human consultation card snapshot`
- `human consultation metadata never stores a consultant user id`

Use metadata with one institution resource UUID and assert replay performs zero saves, zero candidate queries, and zero provider calls. Assert `institutionId` and `canChatWithHuman=true` survive unchanged.

- [ ] **Step 4: Add SSE unit coverage for a completed deterministic turn**

In `AgentStreamingServiceTest`, construct `PreparedChatTurn.Completed` with the new intent and assert:

```kotlin
assertEquals(listOf("started", "completed"), sink.order)
assertFalse(sink.events.any { it is AgentStreamEvent.Delta })
verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
provider.verify()
```

- [ ] **Step 5: Run the focused non-database compatibility tests**

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.chat.service.ChatServiceContextRoutingTest" --tests "com.joysong.server.agent.AgentWorkflowCoreTest" --tests "com.joysong.server.agent.AgentStreamingServiceTest" --console=plain
```

Expected: summary validation, exact metadata, replay, and `started -> completed` with no delta pass.

- [ ] **Step 6: Add one isolated integration slice**

In `AgentChatFlowIntegrationTest`, seed one verified institution, one non-deleted consultant user, and one approved/non-revoked consultant membership in the class's guarded Testcontainers database. Add cases named `HTTP human consultation returns and restores a fixed institution handoff without model calls` and `HTTP human consultation stream emits started then completed without delta`.

For REST assert:

```text
$.data.intent = HUMAN_CONSULTATION
$.data.queryTarget = INSTITUTION
$.data.nextAction = SELECT_INSTITUTION
$.data.catalogItems[0].type = INSTITUTION
$.data.catalogItems[0].institutionId = the seeded institution ID
$.data.catalogItems[0].canChatWithHuman = true
```

Fetch message history and assert the same institution-card snapshot returns. For SSE assert ordered `event:started`, `event:completed`, no `event:delta`, and `streamingProviderCallCount()==0`.

- [ ] **Step 7: Run the guarded integration class once**

Before the command, verify its existing startup output still prints `AGENT_CHAT_TEST_DB_HOST` and `AGENT_CHAT_TEST_DB_NAME` and rejects names outside `myapp_worktree_*`.

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --tests "com.joysong.server.agent.AgentChatFlowIntegrationTest" --console=plain
```

Do not rerun this passing class later. If Docker is unavailable, record the exact environmental failure and do not weaken or bypass the database guard.

- [ ] **Step 8: Commit the persistence and transport slice**

```powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/agent/context/AgentContextBuilder.kt joysong-server/src/test/kotlin/com/joysong/server/chat/service/ChatServiceContextRoutingTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentWorkflowCoreTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentStreamingServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/agent/AgentChatFlowIntegrationTest.kt
git commit -m "test: cover human handoff lifecycle"
```

---

### Task 5: Render only safe institution consultation actions in Flutter

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart:4-73,102-297`
- Modify: `joysong-flutter/test/features/agent/agent_catalog_cards_test.dart`

**Interfaces:**
- Replaces the human callback's raw catalog-item argument with a validated institution ID string.
- Uses one resolver for ordinary link cards, detail cards, and report-card actions.

- [ ] **Step 1: Write the failing safe-ID matrix widget tests**

Add widget cases named:

- `ordinary institution action prefers institutionId and falls back to item id`
- `institution project action emits only its explicit institutionId`
- `doctor project unknown and blank ids never expose human consultation`
- `human consultation requires callback and canChatWithHuman`
- `catalog report action emits the safe institution id`

In the negative matrix deliberately include a doctor with both a resource ID and an institution ID:

```dart
const AgentCatalogItem(
  type: 'DOCTOR',
  id: 'doctor-record-1',
  name: 'Doctor',
  subtitle: '',
  summary: '',
  attributes: {},
  institutionId: 'institution-must-not-leak',
  projectId: null,
  canChatWithHuman: true,
)
```

Assert no human action is present and no callback fires.

- [ ] **Step 2: Run the catalog-card test and confirm RED**

From `joysong-flutter`:

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_catalog_cards_test.dart
```

Expected failure: ordinary link cards do not expose the callback and current report/detail callbacks receive an unchecked item.

- [ ] **Step 3: Add one safe resolver and ID-only callback**

At the top of `agent_catalog_cards.dart`, add:

```dart
typedef AgentHumanConsultationAction = void Function(String institutionId);

String? _humanConsultationInstitutionId(AgentCatalogItem item) {
  final explicitInstitutionId = item.institutionId?.trim() ?? '';
  final itemId = item.id.trim();
  return switch (item.type.trim().toUpperCase()) {
    'INSTITUTION' => explicitInstitutionId.isNotEmpty
        ? explicitInstitutionId
        : (itemId.isEmpty ? null : itemId),
    'INSTITUTION_PROJECT' =>
      explicitInstitutionId.isEmpty ? null : explicitInstitutionId,
    _ => null,
  };
}
```

Change every human callback field to `AgentHumanConsultationAction?`. For each item calculate:

```dart
final institutionId = item.canChatWithHuman
    ? _humanConsultationInstitutionId(item)
    : null;
final canConsult = onHumanChat != null && institutionId != null;
```

The action must call only:

```dart
onHumanChat!(institutionId);
```

Give each action a stable key:

```dart
ValueKey('agent-human-consult-${item.type}-${item.id}')
```

Add `onHumanChat` to `AgentCatalogLinkList` and `AgentCatalogLinkCard`, pass it down, and render the same localized `真人咨询 / Ask a specialist` action below the ordinary link row. Preserve the existing detail-navigation `InkWell` separately.

- [ ] **Step 4: Apply the same guard to detail and report paths**

Replace direct `item.canChatWithHuman && onHumanChat != null` checks in `AgentCatalogDetailCard` and `AgentCatalogReportCard` with `canConsult`. Do not pass `item.id`, `item.institutionId`, or an `AgentCatalogItem` beyond the resolver.

- [ ] **Step 5: Format and run the focused card test**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\dart.bat format lib/features/agent/presentation/agent_catalog_cards.dart test/features/agent/agent_catalog_cards_test.dart
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_catalog_cards_test.dart
```

Expected: all safe-mapping and rendering cases pass.

- [ ] **Step 6: Commit the safe card slice**

```powershell
git add -- joysong-flutter/lib/features/agent/presentation/agent_catalog_cards.dart joysong-flutter/test/features/agent/agent_catalog_cards_test.dart
git commit -m "feat: add safe agent consultation actions"
```

---

### Task 6: Wire Agent messages into the existing consultant picker and DM flow

**Files:**
- Modify: `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart:22-40,208-225`
- Modify: `joysong-flutter/lib/features/shell/presentation/app_shell.dart:270-275,460-474,838-879`
- Modify: `joysong-flutter/test/features/agent/agent_chat_page_test.dart`
- Modify: `joysong-flutter/test/features/shell/app_shell_navigation_test.dart`
- Verify unchanged: `joysong-flutter/test/features/shell/institution_consultant_picker_test.dart`

**Flow:**

```text
Agent institution card -> validated institution ID -> existing picker
-> GET current consultants -> selected BookingConsultant.id -> existing createDmConversation
-> existing DM thread
```

- [ ] **Step 1: Write failing page forwarding and restored-history tests**

Update the test helper to accept `AgentHumanConsultationAction?`, then add widget cases named:

- `ordinary institution consultation forwards the safe institution id`
- `comparison report consultation forwards the safe institution id`
- `restored institution card remains actionable`
- `doctor catalog record id is never forwarded as a consultation target`

The restored assistant fixture contains only an institution catalog snapshot; it must not contain a `BookingConsultant` or consultant/user ID.

- [ ] **Step 2: Write a failing AppShell flow test through a recording ApiClient**

`AppShell` constructs its repositories privately from `ApiClient`; do not pretend they can be injected. Add `_AgentHandoffApiClient extends ApiClient` in `app_shell_navigation_test.dart` and override `get`/`post` with path-specific decoded fixtures:

```dart
final class _AgentHandoffApiClient extends ApiClient {
  _AgentHandoffApiClient()
      : super(apiRoot: Uri.parse('http://localhost/api/'));

  final posts = <(String, Object?)>[];

  @override
  Future<T?> get<T>(
    String path, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    final data = switch (path) {
      'notifications/unread-count' => 0,
      'notifications' || 'dm/conversations' => const <Object?>[],
      'chat/sessions' => <Object?>[agentSessionJson],
      'chat/sessions/session-human/messages' => <Object?>[agentInstitutionMessageJson],
      'discover/institutions/institution-1/consultants' => <Object?>[
          {'id': 'consultant-2', 'name': '林顾问'},
        ],
      _ => const <Object?>[],
    };
    return decodeData(data);
  }

  @override
  Future<T?> post<T>(
    String path, {
    Object? body,
    required T Function(Object? json) decodeData,
  }) async {
    posts.add((path, body));
    if (path != 'dm/conversations') return decodeData(const <String, Object?>{});
    return decodeData(dmConversationJson);
  }
}
```

Provide complete JSON fixtures accepted by `ChatSession.fromJson`, `ChatMessage.fromJson`, and `DmConversation.fromJson`. Pump `AppShell(apiClient: client, currentUserId: 'user-1')`, open the Messages tab, tap the `颜颜` entry, tap the institution handoff action, select `林顾问`, and assert:

```dart
final dmPost = client.posts.singleWhere((request) => request.$1 == 'dm/conversations');
expect(dmPost.$2, {'targetId': 'consultant-2'});
```

Do not add repository injection or a new coordinator solely for this test. Retain the existing production duplicate-sheet guard and current picker tests.

- [ ] **Step 3: Run the two changed test files and confirm RED**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_page_test.dart test/features/shell/app_shell_navigation_test.dart
```

Expected failure: `onHumanConsult` is unused and AppShell does not pass it.

- [ ] **Step 4: Wire both Agent presentation branches**

In `AgentChatPage`, change the field to:

```dart
final AgentHumanConsultationAction? onHumanConsult;
```

Pass it to both branches:

```dart
AgentCatalogReportCard(
  report: message.catalogReport!,
  onOpen: widget.onOpenCatalogItem,
  onHumanChat: widget.onHumanConsult,
  canOpen: _canOpenCatalogItem,
)
```

```dart
AgentCatalogLinkList(
  items: _supportedCatalogItems(message),
  onOpen: widget.onOpenCatalogItem,
  onHumanChat: widget.onHumanConsult,
  canOpen: _canOpenCatalogItem,
)
```

Do not reuse `_canOpenCatalogItem` as the human-target predicate; opening resource details and resolving a safe institution target are different checks.

- [ ] **Step 5: Centralize and wire both AppShell Agent constructors**

Remove the duplicated `AgentChatPage` construction with one private helper:

```dart
AgentChatPage _buildAgentChatPage({
  ChatContextType? initialContextType,
  String? initialContextId,
  String? initialContextName,
}) =>
    AgentChatPage(
      chatController: _agentChatController!,
      planController: _agentPlanController!,
      initialContextType: initialContextType,
      initialContextId: initialContextId,
      initialContextName: initialContextName,
      onOpenCatalogItem: _openAgentCatalogItem,
      onHumanConsult: _bookingRepository == null || _messagingRepository == null
          ? null
          : _openInstitutionConsultants,
    );
```

Use `_buildAgentChatPage()` in the Agent-tab fallback and call it with the resolved detail context from `_openAiChat`. This makes both construction sites share the tested callback wiring instead of duplicating it.

The effective callback remains:

```dart
onHumanConsult: _bookingRepository == null || _messagingRepository == null
    ? null
    : _openInstitutionConsultants,
```

Keep `_openInstitutionConsultants`, `openInstitutionConsultantChat`, `_openDirectMessage`, self-message prevention, and `_institutionConsultantPickerOpen` unchanged. Keep `buildAgentCatalogDetailPage` without a doctor-consult callback; Agent doctor resource IDs must never enter `_openDoctorChat`.

- [ ] **Step 6: Format and run the focused Flutter tests**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\dart.bat format lib/features/agent/presentation/agent_chat_page.dart lib/features/shell/presentation/app_shell.dart test/features/agent/agent_chat_page_test.dart test/features/shell/app_shell_navigation_test.dart
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/agent/agent_chat_page_test.dart test/features/shell/app_shell_navigation_test.dart
```

- [ ] **Step 7: Run the existing picker regression once**

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat test test/features/shell/institution_consultant_picker_test.dart
```

Expected: loading, long list, empty state, retry, dismissal, consultant selection, and the selected consultant ID/name passed to DM remain green.

- [ ] **Step 8: Commit the end-to-end Flutter wiring**

```powershell
git add -- joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/test/features/agent/agent_chat_page_test.dart joysong-flutter/test/features/shell/app_shell_navigation_test.dart
git commit -m "feat: open consultant picker from agent"
```

---

### Task 7: Update Agent documentation and regenerate the sequence diagram

**Files:**
- Modify: `docs/AI_AGENT_DEVELOPMENT.md:33-68`
- Modify: `docs/AI_AGENT_TESTING.md`
- Modify: `design/AI_AGENT_SEQUENCE.puml`
- Regenerate: `design/AI_AGENT_SEQUENCE.png`

- [ ] **Step 1: Update the development and testing contracts**

Document all of the following explicitly:

- local high-precision phrases plus optional intent-model classification;
- safety/negation precedence and fixed `INSTITUTION` target;
- one set-based candidate query and ranking tiers;
- fixed localized turn with zero answer-model calls;
- empty result uses `nextAction=NONE`;
- REST/SSE share persistence, with SSE `started -> completed` and no answer delta;
- Agent history stores institution snapshots only;
- a tap reloads current consultants and only `BookingConsultant.id` can become a DM target;
- doctor/project/unknown IDs are never messaging IDs;
- the first release continues to use the generic user-pair DM and does not store institution attribution or atomically revalidate membership during DM creation.

Replace the stale statement that SSE is disabled. Record focused commands relative to the current worktree rather than the old hard-coded worktree path.

- [ ] **Step 2: Update the PlantUML source**

Remove deprecated `skinparam ParticipantPadding`. Add `HUMAN_CONSULTATION`, `SELECT_INSTITUTION`, a single eligibility query, deterministic non-model completion, empty-candidate branch, institution choice, current consultant lookup, selected consultant user ID, existing DM creation/navigation, and historical reload behavior.

Use this branch in the intent-execution section:

```plantuml
else final intent == HUMAN_CONSULTATION
    Chat -> DB: 一次集合查询最多4个可咨询机构
    DB --> Chat: 已验证机构 + 当前合格咨询师存在性
    alt 有候选机构
        Chat -> Lifecycle: completeTurn(固定双语文案,\nSELECT_INSTITUTION, institution cards)
    else 全国无候选机构
        Chat -> Lifecycle: completeTurn(固定空态,\nNONE, empty cards)
    end
    note right of Chat
    回答模型调用 = 0
    原始真人咨询短语不进入普通 catalog search
    end note
```

In the Flutter section show that doctor/project/unknown/blank identifiers expose no handoff action and that historical taps query current consultants again.

- [ ] **Step 3: Validate and regenerate the PNG with a pinned PlantUML JAR**

From the worktree root:

```powershell
$plantUmlJar = Join-Path ([IO.Path]::GetTempPath()) 'joysong-plantuml-1.2026.6.jar'
try {
    Invoke-WebRequest -Uri 'https://github.com/plantuml/plantuml/releases/download/v1.2026.6/plantuml-1.2026.6.jar' -OutFile $plantUmlJar
    java -jar $plantUmlJar -charset UTF-8 -checkonly design\AI_AGENT_SEQUENCE.puml
    java -jar $plantUmlJar -charset UTF-8 -tpng design\AI_AGENT_SEQUENCE.puml
} finally {
    if (Test-Path -LiteralPath $plantUmlJar) {
        Remove-Item -LiteralPath $plantUmlJar
    }
}
Get-Item design\AI_AGENT_SEQUENCE.png | Select-Object FullName,Length,LastWriteTime
```

If the download is blocked, request network approval once; do not hand-edit the PNG or leave the JAR in the repository.

- [ ] **Step 4: Visually verify the generated diagram**

Open `D:\code\kotlin\joysong\.worktrees\agent-human-consultation\design\AI_AGENT_SEQUENCE.png` with the image viewer and confirm:

- no PlantUML warning banner;
- no clipped or overlapping participants/arrows;
- Chinese text renders correctly;
- human intent, empty state, zero answer-model call, institution choice, consultant lookup, DM navigation, and history reload are visible.

- [ ] **Step 5: Commit docs and diagram together**

```powershell
git add -- docs/AI_AGENT_DEVELOPMENT.md docs/AI_AGENT_TESTING.md design/AI_AGENT_SEQUENCE.puml design/AI_AGENT_SEQUENCE.png
git commit -m "docs: document agent human handoff"
```

---

### Task 8: Final verification and review handoff

**Files:**
- Verify all changed files; add no new production scope.

- [ ] **Step 1: Run Kotlin formatting/static checks defined by the project**

Inspect `joysong-server/build.gradle.kts` for an existing formatting or lint task. Run only an existing task; do not add a formatter dependency for this feature. Always run:

```powershell
git diff --check
```

- [ ] **Step 2: Run one backend full suite at most once**

Skip any focused command already recorded as passing. From `joysong-server`:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\shimeng\.gradle'
.\gradlew.bat --offline --no-daemon test --console=plain
```

Stop after ten minutes if still running, then report completed tasks and the slowest observed tests. Do not retry environment/flaky failures without new evidence.

- [ ] **Step 3: Run Flutter analysis once**

From `joysong-flutter`:

```powershell
D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat analyze
```

Do not rerun already-passing focused widget tests.

- [ ] **Step 4: Inspect scope and schema invariants**

```powershell
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/agent-human-consultation status --short
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/agent-human-consultation diff --stat master...HEAD
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/agent-human-consultation diff --name-only master...HEAD
git -c safe.directory=D:/code/kotlin/joysong/.worktrees/agent-human-consultation diff --check master...HEAD
```

Confirm there is no migration, no consultant ID in Agent metadata fixtures, no new endpoint/route, no admin change, no unrelated main-worktree file, and no temporary PlantUML/Gradle artifact.

- [ ] **Step 5: Request code review**

Use `superpowers:requesting-code-review` against the approved design and this plan. Resolve actionable findings with the narrowest relevant test; do not perform unrelated refactors.

- [ ] **Step 6: Prepare branch handoff**

Use `superpowers:finishing-a-development-branch` only after all required checks are green or explicitly recorded as environment-blocked. Report commits, test evidence, any skipped full-suite reason, and remind the user that `design/AI_AGENT_SEQUENCE.puml` and PNG were updated with the implementation.
