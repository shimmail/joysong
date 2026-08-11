package com.joysong.server.agent.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class AgentIntentRouterTest {
    private val router = AgentIntentRouter()

    @Test
    fun `comparison routes to catalog search`() {
        val result = router.decide("对比上海的热玛吉机构项目", "GENERAL")
        assertEquals(AgentIntent.COMPARISON, result.intent)
        assertEquals(AgentQueryTarget.INSTITUTION_PROJECT, result.queryTarget)
        assertTrue(result.searchCatalog)
    }

    @Test
    fun `detail summary request routes to detail summary`() {
        val result = router.decide("帮我简单介绍一下", "DOCTOR")
        assertEquals(AgentIntent.DETAIL_SUMMARY, result.intent)
        assertTrue(result.searchCatalog)
    }

    @Test
    fun `follow up in detail context stays on catalog question path`() {
        val result = router.decide("恢复期多久", "PROJECT")
        assertEquals(AgentIntent.CATALOG_QA, result.intent)
        assertEquals(AgentQueryTarget.PROJECT, result.queryTarget)
        assertTrue(result.searchCatalog)
    }

    @Test
    fun `assessment keeps legacy confidence after detail context completes the route`() {
        val result = router.assess("恢复期多久", "PROJECT")

        assertEquals(AgentIntent.CATALOG_QA, result.decision.intent)
        assertEquals(AgentQueryTarget.PROJECT, result.decision.queryTarget)
        assertEquals(0.80, result.confidence)
        assertFalse(result.requiresLlmParsing)
        assertFalse(result.requiresContextCompletion)
    }

    @Test
    fun `safety takes precedence and avoids recommendations`() {
        val result = router.decide("我在孕期，可以做热玛吉吗", "GENERAL")
        assertEquals(AgentIntent.SAFETY_SCREENING, result.intent)
        assertFalse(result.searchCatalog)
        assertEquals(AgentNextAction.COMPLETE_SAFETY_SCREENING, result.nextAction)
    }

    @Test
    fun `plain conversation does not search catalog`() {
        val result = router.decide("你好，今天心情不错", "GENERAL")
        assertEquals(AgentIntent.GENERAL_CHAT, result.intent)
        assertFalse(result.searchCatalog)
    }

    @Test
    fun `clear catalog request stays on fast local path`() {
        val result = router.assess("对比上海的热玛吉机构项目", "GENERAL")
        assertFalse(result.requiresLlmParsing)
        assertTrue(result.confidence >= 0.70)
    }

    @Test
    fun `implicit multi constraint request asks for structured parsing`() {
        val result = router.assess("预算一万，不想恢复太久，脸有点垮，应该怎么弄", "GENERAL")
        assertTrue(result.requiresLlmParsing)
        assertTrue(result.ambiguityReasons.isNotEmpty())
    }

    @Test
    fun `local safety route never requires llm parsing`() {
        val result = router.assess("我正在哺乳，可以做项目吗", "GENERAL")
        assertEquals(AgentIntent.SAFETY_SCREENING, result.decision.intent)
        assertFalse(result.requiresLlmParsing)
    }

    @ParameterizedTest(name = "negation: {0}")
    @MethodSource("negationRoutingCases")
    fun `current message ignores explicitly negated intent and target terms`(
        query: String,
        expectedIntent: AgentIntent,
        expectedTarget: AgentQueryTarget?
    ) {
        val result = router.assessCurrent(query, "GENERAL")

        assertEquals(expectedIntent, result.decision.intent)
        assertEquals(expectedTarget, result.decision.queryTarget)
        assertTrue(result.explicitIntent)
        assertTrue(result.explicitQueryTarget)
    }

    @Test
    fun `current message summary routes without target outside detail context`() {
        val result = router.assessCurrent("Don't compare them, just summarize this page", "GENERAL")

        assertEquals(AgentIntent.DETAIL_SUMMARY, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertTrue(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
        assertTrue(result.requiresContextCompletion)
    }

    @Test
    fun `context does not replace explicit Chinese comparison target`() {
        val result = router.supplementWithContext(
            router.assessCurrent("对比机构", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
    }

    @Test
    fun `context completes missing Chinese comparison target`() {
        val result = router.supplementWithContext(
            router.assessCurrent("对比一下", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertFalse(result.requiresContextCompletion)
    }

    @Test
    fun `context does not replace explicit English comparison target`() {
        val result = router.supplementWithContext(
            router.assessCurrent("Compare clinics", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
    }

    @Test
    fun `context completes missing English comparison target`() {
        val result = router.supplementWithContext(
            router.assessCurrent("Compare them", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertFalse(result.requiresContextCompletion)
    }

    @Test
    fun `conflicting context retains local candidate and requests parsing`() {
        val result = router.supplementWithContext(
            router.assessCurrent("对比医生", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertTrue("CONFLICTING_CONTEXT" in result.ambiguityReasons)
        assertTrue(result.requiresLlmParsing)
    }

    @ParameterizedTest(name = "unnegated safety: {0}")
    @MethodSource("unnegatedSafetyCases")
    fun `current message keeps unnegated safety signals at highest priority`(query: String) {
        val result = router.assessCurrent(query, "GENERAL")

        assertEquals(AgentIntent.SAFETY_SCREENING, result.decision.intent)
        assertFalse(result.requiresLlmParsing)
    }

    @Test
    fun `ambiguous double negative requires later parsing`() {
        val result = router.assessCurrent("我不是不想比较项目", "GENERAL")

        assertTrue("AMBIGUOUS_NEGATION" in result.ambiguityReasons)
        assertTrue(result.requiresLlmParsing)
    }

    @Test
    fun `ambiguous safety negation allows only a parser safety upgrade`() {
        val local = router.assessCurrent("I am not not pregnant; compare treatments", "GENERAL")

        val result = router.mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.SAFETY_SCREENING, null, emptyList())
        )

        assertEquals(AgentIntent.SAFETY_SCREENING, result.intent)
        assertEquals(null, result.queryTarget)
    }

    @Test
    fun `conflicting current targets remain parser resolvable`() {
        val local = router.assessCurrent("对比医生和机构", "GENERAL")

        val result = router.mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.COMPARISON, AgentQueryTarget.INSTITUTION, emptyList())
        )

        assertFalse(local.explicitQueryTarget)
        assertEquals(AgentQueryTarget.INSTITUTION, result.queryTarget)
    }

    @Test
    fun `conflicting context candidates retain fallback and require parsing`() {
        val result = router.supplementWithContext(
            router.assessCurrent("对比一下", "GENERAL"),
            listOf(
                router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR),
                router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION)
            )
        )

        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertTrue("CONFLICTING_CONTEXT" in result.ambiguityReasons)
        assertTrue(result.requiresLlmParsing)
    }

    @Test
    fun `english routing terms require word boundaries`() {
        val result = router.assessCurrent("Tell me about hospitality", "GENERAL")

        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
    }

    @Test
    fun `separate negations in one clause remain deterministic`() {
        val result = router.assessCurrent("我不看医生不比较项目，推荐机构", "GENERAL")

        assertEquals(AgentIntent.CATALOG_QA, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertFalse("AMBIGUOUS_NEGATION" in result.ambiguityReasons)
        assertFalse(result.requiresLlmParsing)
    }

    @Test
    fun `non routing negator does not make a later catalog request ambiguous`() {
        val result = router.assessCurrent("I don't know, recommend clinics", "GENERAL")

        assertEquals(AgentIntent.CATALOG_QA, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertFalse("AMBIGUOUS_NEGATION" in result.ambiguityReasons)
        assertFalse(result.requiresLlmParsing)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routeCases")
    fun `routes common Chinese and English requests`(
        query: String,
        expectedIntent: AgentIntent,
        expectedTarget: AgentQueryTarget?,
        expectedSearch: Boolean
    ) {
        val result = router.decide(query, "GENERAL")
        assertEquals(expectedIntent, result.intent)
        assertEquals(expectedTarget, result.queryTarget)
        assertEquals(expectedSearch, result.searchCatalog)
    }

    @ParameterizedTest(name = "risk: {0}")
    @MethodSource("safetyCases")
    fun `safety signals always bypass catalog search`(query: String) {
        val result = router.assess(query, "GENERAL")
        assertEquals(AgentIntent.SAFETY_SCREENING, result.decision.intent)
        assertEquals(AgentNextAction.COMPLETE_SAFETY_SCREENING, result.decision.nextAction)
        assertFalse(result.decision.searchCatalog)
        assertFalse(result.requiresLlmParsing)
    }

    companion object {
        @JvmStatic
        fun routeCases(): Stream<Arguments> = Stream.of(
            Arguments.of("深圳有哪些机构", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION, true),
            Arguments.of("上海有哪些热玛吉医生", AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR, true),
            Arguments.of("有热玛吉项目吗", AgentIntent.CATALOG_QA, AgentQueryTarget.PROJECT, true),
            Arguments.of("对比上海的热玛吉机构项目", AgentIntent.COMPARISON, AgentQueryTarget.INSTITUTION_PROJECT, true),
            Arguments.of("帮我规划适合自己的项目", AgentIntent.PLANNING, AgentQueryTarget.PROJECT, true),
            Arguments.of("Hello", AgentIntent.GENERAL_CHAT, null, false),
            Arguments.of("Compare Thermage clinic packages", AgentIntent.COMPARISON, AgentQueryTarget.INSTITUTION_PROJECT, true),
            Arguments.of("Which doctors offer Botox?", AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR, true),
            Arguments.of("Find a clinic in Shanghai", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION, true),
            Arguments.of("What treatments help with acne?", AgentIntent.CATALOG_QA, AgentQueryTarget.PROJECT, true)
        )

        @JvmStatic
        fun safetyCases(): Stream<String> = Stream.of(
            "我在孕期，可以做热玛吉吗", "正在哺乳，可以打针吗", "我有严重过敏史",
            "皮肤感染了还能做项目吗", "我正在服药，可以做激光吗", "我是瘢痕体质",
            "I am pregnant, can I get Botox?", "I am breastfeeding", "I have a severe allergy",
            "I am taking medication", "I have a skin infection", "Can you guarantee a 100% result?"
        )

        @JvmStatic
        fun negationRoutingCases(): Stream<Arguments> = Stream.of(
            Arguments.of("不看医生，推荐机构", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION),
            Arguments.of("不要方案，只比较项目", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
            Arguments.of("我没有怀孕，想比较项目", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
            Arguments.of("无需医生，推荐机构", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION),
            Arguments.of("I don't want a doctor; show me clinics", AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION),
            Arguments.of("I am not pregnant; compare treatments", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
            Arguments.of("She isn't pregnant; compare treatments", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT),
            Arguments.of("No pregnancy; compare treatments", AgentIntent.COMPARISON, AgentQueryTarget.PROJECT)
        )

        @JvmStatic
        fun unnegatedSafetyCases(): Stream<String> = Stream.of(
            "我怀孕了，想比较项目", "我有严重过敏史，推荐机构", "我正在服药，可以治疗吗",
            "我不想做项目因为正在服药",
            "I am pregnant; compare treatments", "I have a severe allergy; show me clinics", "I am taking medication; compare treatments",
            "I don't want treatments because I am pregnant"
        )
    }
}
