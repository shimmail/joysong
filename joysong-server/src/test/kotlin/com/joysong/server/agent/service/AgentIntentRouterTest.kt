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
    fun `detail context routes to detail summary`() {
        val result = router.decide("帮我简单介绍一下", "DOCTOR")
        assertEquals(AgentIntent.DETAIL_SUMMARY, result.intent)
        assertTrue(result.searchCatalog)
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
    }
}
