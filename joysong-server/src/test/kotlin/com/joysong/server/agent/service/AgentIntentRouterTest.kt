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
    fun `current scan retains safety and comparison evidence`() {
        val safetyComparison = router.assessCurrent("怀孕期间比较两个机构", "GENERAL")

        assertEquals(
            setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.COMPARISON),
            safetyComparison.positiveIntents()
        )
        assertEquals(setOf(AgentQueryTarget.INSTITUTION), safetyComparison.positiveTargets())
        assertEquals(AgentIntent.SAFETY_SCREENING, safetyComparison.decision.intent)
    }

    @Test
    fun `current scan retains comparison and planning evidence`() {
        val comparisonPlanning = router.assessCurrent("比较这些项目并制定方案", "GENERAL")

        assertEquals(
            setOf(AgentIntent.COMPARISON, AgentIntent.PLANNING),
            comparisonPlanning.positiveIntents()
        )
        assertEquals(AgentIntent.COMPARISON, comparisonPlanning.decision.intent)
    }

    @Test
    fun `explicit catalog actions remain separate from generic entity nouns`() {
        listOf(
            "比较项目并推荐医生",
            "Compare treatments and recommend doctors"
        ).forEach { query ->
            val result = router.assessCurrent(query, "GENERAL")

            assertEquals(setOf(AgentIntent.CATALOG_QA, AgentIntent.COMPARISON), result.positiveIntents())
            assertEquals(setOf(AgentQueryTarget.DOCTOR, AgentQueryTarget.PROJECT), result.positiveTargets())
            assertEquals(AgentIntent.COMPARISON, result.decision.intent)
            assertTrue(result.decision.searchCatalog)
        }

        val genericNoun = router.assessCurrent("Compare clinics", "GENERAL")
        assertEquals(setOf(AgentIntent.COMPARISON), genericNoun.positiveIntents())
    }

    @Test
    fun `ambiguous target does not unlock independent labels`() {
        val independent = router.assessCurrent("I do not not want a doctor; compare clinics", "GENERAL")

        assertTrue(independent.evidenceFor(AgentIntent.COMPARISON).locked)
        assertTrue(independent.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
        assertEquals(AgentLabelPolarity.UNCERTAIN, independent.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
    }

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
    fun `comparison stays primary over summary outside a detail context`() {
        val result = router.assessCurrent("Compare clinics and summarize the differences", "GENERAL")

        assertEquals(setOf(AgentIntent.COMPARISON, AgentIntent.DETAIL_SUMMARY), result.positiveIntents())
        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertTrue(result.decision.searchCatalog)
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
    fun `unrelated general chat does not inherit a detail session target`() {
        val current = router.assessCurrent("换个话题，讲个笑话", "DOCTOR")

        assertEquals(AgentIntent.GENERAL_CHAT, current.decision.intent)
        assertEquals(null, current.decision.queryTarget)
        assertFalse(current.requiresContextCompletion)

        val completed = router.decide("换个话题，讲个笑话", "DOCTOR")
        assertEquals(AgentIntent.GENERAL_CHAT, completed.intent)
        assertEquals(null, completed.queryTarget)
        assertFalse(completed.searchCatalog)
    }

    @Test
    fun `negated current references do not inherit a detail session target`() {
        listOf(
            "不要这家" to "INSTITUTION",
            "不是这个医生" to "DOCTOR",
            "不要推荐这个医生" to "DOCTOR",
            "别再推荐这个医生" to "DOCTOR",
            "不要再给我看这家" to "INSTITUTION",
            "这家不要了" to "INSTITUTION",
            "我不想选这家" to "INSTITUTION",
            "这家我不要了" to "INSTITUTION",
            "不要这家因为太远" to "INSTITUTION",
            "do not use this clinic" to "INSTITUTION",
            "do not recommend this doctor" to "DOCTOR",
            "don't show me this doctor" to "DOCTOR",
            "I don't want this clinic because it is too expensive" to "INSTITUTION",
            "I don't want this one" to "DOCTOR"
        ).forEach { (query, contextType) ->
            val current = router.assessCurrent(query, contextType)

            assertTrue("NEGATED_CURRENT_REFERENCE" in current.ambiguityReasons, query)
            assertFalse("UNRESOLVED_CURRENT_REFERENCE" in current.ambiguityReasons, query)

            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.GENERAL_CHAT, completed.intent, query)
            assertEquals(null, completed.queryTarget, query)
            assertFalse(completed.searchCatalog, query)
        }
    }

    @Test
    fun `generic time chat does not become a detail follow up`() {
        val current = router.assessCurrent("换个话题，聊聊时间", "PROJECT")

        assertEquals(AgentIntent.GENERAL_CHAT, current.decision.intent)
        assertFalse("DETAIL_CONTEXT_FOLLOW_UP" in current.ambiguityReasons)
        assertFalse(current.requiresContextCompletion)
    }

    @Test
    fun `same target constraints remain detail follow ups without a catalog action`() {
        listOf(
            Triple("医生价格多少", "DOCTOR", AgentQueryTarget.DOCTOR),
            Triple("机构费用多少", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("项目恢复期多久", "PROJECT", AgentQueryTarget.PROJECT)
        ).forEach { (query, contextType, target) ->
            val current = router.assessCurrent(query, contextType)

            assertTrue("DETAIL_CONTEXT_FOLLOW_UP" in current.ambiguityReasons, query)
            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.CATALOG_QA, completed.intent, query)
            assertEquals(target, completed.queryTarget, query)
        }
    }

    @Test
    fun `same target attribute questions remain on the entry detail`() {
        listOf(
            Triple("医生资质怎么样", "DOCTOR", AgentQueryTarget.DOCTOR),
            Triple("机构有哪些优势", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("多久能恢复？", "PROJECT", AgentQueryTarget.PROJECT),
            Triple("恢复期可以吗", "PROJECT", AgentQueryTarget.PROJECT),
            Triple("How long can recovery take?", "PROJECT", AgentQueryTarget.PROJECT)
        ).forEach { (query, contextType, target) ->
            val current = router.assessCurrent(query, contextType)

            assertTrue("DETAIL_CONTEXT_FOLLOW_UP" in current.ambiguityReasons, query)
            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.CATALOG_QA, completed.intent, query)
            assertEquals(target, completed.queryTarget, query)
        }
    }

    @Test
    fun `alternative entity requests switch catalog focus instead of summarizing the entry detail`() {
        listOf(
            Triple("推荐另外几家", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("推荐其他的", "DOCTOR", AgentQueryTarget.DOCTOR),
            Triple("介绍其他机构", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("换一家机构", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("换个医生", "DOCTOR", AgentQueryTarget.DOCTOR),
            Triple("换一家", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("换个", "DOCTOR", AgentQueryTarget.DOCTOR),
            Triple("换机构", "INSTITUTION", AgentQueryTarget.INSTITUTION),
            Triple("换医生", "DOCTOR", AgentQueryTarget.DOCTOR)
        ).forEach { (query, contextType, expectedTarget) ->
            val current = router.assessCurrent(query, contextType)

            assertTrue("ALTERNATIVE_ENTITY_REQUEST" in current.ambiguityReasons, query)

            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.CATALOG_QA, completed.intent, query)
            assertEquals(expectedTarget, completed.queryTarget, query)
        }
    }

    @Test
    fun `negated alternative target does not suppress a current target conflict`() {
        val current = router.assessCurrent("不要其他机构，推荐医生和项目", "GENERAL")

        assertFalse("ALTERNATIVE_ENTITY_REQUEST" in current.ambiguityReasons)
        assertTrue("CONFLICTING_CURRENT_TARGETS" in current.ambiguityReasons)
        assertTrue(current.requiresLlmParsing)
    }

    @Test
    fun `descriptive alternatives do not release the current detail entity`() {
        listOf(
            "这家机构还有其他优势吗" to "INSTITUTION",
            "这个医生还有其他资质吗" to "DOCTOR",
            "推荐另外几种注意事项" to "PROJECT",
            "介绍另外几个优势" to "INSTITUTION",
            "不要换一家机构" to "INSTITUTION",
            "不要换个" to "DOCTOR",
            "换个话题，聊聊价格" to "INSTITUTION",
            "我还有另一个问题，转真人咨询" to "INSTITUTION",
            "I have another question; connect me to a person" to "INSTITUTION"
        ).forEach { (query, contextType) ->
            val current = router.assessCurrent(query, contextType)

            assertFalse("ALTERNATIVE_ENTITY_REQUEST" in current.ambiguityReasons, query)
        }
    }

    @Test
    fun `price confirmation does not negate the current detail reference`() {
        listOf(
            "不是这个价格吗？" to "PROJECT",
            "不是这个项目更贵吗？" to "PROJECT",
            "Isn't this treatment more expensive?" to "PROJECT",
            "Isn't this treatment more expensive than that one?" to "PROJECT",
            "Isn't this treatment more expensive than the other one?" to "PROJECT",
            "Isn't this treatment cheaper than that one?" to "PROJECT",
            "Isn't this project more expensive right?" to "PROJECT",
            "It is not that expensive, right?" to "PROJECT"
        ).forEach { (query, contextType) ->
            val current = router.assessCurrent(query, contextType)

            assertFalse("NEGATED_CURRENT_REFERENCE" in current.ambiguityReasons, query)
            assertFalse(
                current.targetEvidence.any {
                    it.target == AgentQueryTarget.PROJECT && it.polarity == AgentLabelPolarity.NEGATIVE
                },
                query
            )
            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.CATALOG_QA, completed.intent, query)
            assertEquals(AgentQueryTarget.PROJECT, completed.queryTarget, query)
        }
    }

    @Test
    fun `unrelated recovery wording does not inherit an institution detail`() {
        val current = router.assessCurrent("心情不好，多久能恢复？", "INSTITUTION")

        assertEquals(AgentIntent.GENERAL_CHAT, current.decision.intent)
        assertFalse("DETAIL_CONTEXT_FOLLOW_UP" in current.ambiguityReasons)
        assertFalse(current.requiresContextCompletion)
    }

    @Test
    fun `explicit topic boundary does not request the entry detail context`() {
        val current = router.assessCurrent("换个话题，聊聊价格", "INSTITUTION")

        assertTrue("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons)
        assertEquals(AgentIntent.GENERAL_CHAT, current.decision.intent)
        assertEquals(null, current.decision.queryTarget)
        assertFalse(current.requiresContextCompletion)
        assertFalse(current.requiresLlmParsing)
    }

    @Test
    fun `explicit topic boundary keeps a new catalog target from the same message`() {
        listOf(
            "换个话题，推荐医生" to AgentQueryTarget.DOCTOR,
            "switch the topic and show clinics" to AgentQueryTarget.INSTITUTION
        ).forEach { (query, expectedTarget) ->
            val current = router.assessCurrent(query, "INSTITUTION")

            assertTrue("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons, query)
            assertEquals(AgentIntent.CATALOG_QA, current.decision.intent, query)
            assertEquals(expectedTarget, current.decision.queryTarget, query)
            assertFalse(current.requiresContextCompletion, query)
        }
    }

    @Test
    fun `explicit topic boundary keeps a new planning intent without inheriting the detail target`() {
        val current = router.assessCurrent("换个话题，预算5000帮我规划", "INSTITUTION")

        assertTrue("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons)
        assertEquals(AgentIntent.PLANNING, current.decision.intent)
        assertEquals(null, current.decision.queryTarget)
        assertFalse(current.requiresContextCompletion)
    }

    @Test
    fun `explicit topic boundary still parses a conflicting new business request`() {
        val current = router.assessCurrent("换个话题，比较医生和机构", "INSTITUTION")

        assertEquals(AgentIntent.COMPARISON, current.decision.intent)
        assertTrue("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons)
        assertTrue("CONFLICTING_CURRENT_TARGETS" in current.ambiguityReasons)
        assertFalse(current.requiresContextCompletion)
        assertTrue(current.requiresLlmParsing)
    }

    @Test
    fun `explicit topic boundary parses uncertain safety but keeps deterministic human handoff`() {
        val safety = router.assessCurrent("换个话题，我不确定是否怀孕，可以做吗", "INSTITUTION")

        assertTrue("EXPLICIT_TOPIC_BOUNDARY" in safety.ambiguityReasons)
        assertEquals(AgentIntent.GENERAL_CHAT, safety.decision.intent)
        assertEquals(null, safety.decision.queryTarget)
        assertFalse(safety.requiresContextCompletion)
        assertTrue(safety.requiresLlmParsing)

        val human = router.assessCurrent("换个话题，我不确定是否需要真人咨询", "INSTITUTION")

        assertTrue("EXPLICIT_TOPIC_BOUNDARY" in human.ambiguityReasons)
        assertEquals(AgentIntent.HUMAN_CONSULTATION, human.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, human.decision.queryTarget)
        assertFalse(human.requiresContextCompletion)
    }

    @Test
    fun `explicit topic boundary still parses current deictic human and aesthetic requests`() {
        listOf(
            "换个话题，Could someone help me with this?",
            "换个话题，我脸垮了怎么办"
        ).forEach { query ->
            val current = router.assessCurrent(query, "INSTITUTION")

            assertTrue("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons, query)
            assertFalse(current.requiresContextCompletion, query)
            assertTrue(current.requiresLlmParsing, query)
        }
    }

    @Test
    fun `short social acknowledgements stay transparent to detail context`() {
        listOf("好的", "收到", "明白了", "ok", "okay").forEach { query ->
            val current = router.assessCurrent(query, "INSTITUTION")

            assertTrue("SOCIAL_INTERJECTION" in current.ambiguityReasons, query)
            assertEquals(AgentIntent.GENERAL_CHAT, current.decision.intent, query)
        }
    }

    @Test
    fun `deictic scope target yields to the requested result target`() {
        listOf(
            Triple("这家机构有哪些项目", "INSTITUTION", AgentQueryTarget.PROJECT),
            Triple("这家机构还有其他项目吗", "INSTITUTION", AgentQueryTarget.PROJECT),
            Triple("这个医生在哪家机构", "DOCTOR", AgentQueryTarget.INSTITUTION)
        ).forEach { (query, contextType, expectedTarget) ->
            val current = router.assessCurrent(query, contextType)

            assertEquals(expectedTarget, current.decision.queryTarget, query)
        }
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
    fun `safety and human consultation stay above alternative catalog requests`() {
        val safety = router.decide("我怀孕了，推荐其他机构", "GENERAL")
        assertEquals(AgentIntent.SAFETY_SCREENING, safety.intent)
        assertFalse(safety.searchCatalog)

        val human = router.decide("帮我转真人咨询，再推荐其他机构", "GENERAL")
        assertEquals(AgentIntent.HUMAN_CONSULTATION, human.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, human.queryTarget)
        assertEquals(AgentNextAction.SELECT_INSTITUTION, human.nextAction)
        assertFalse(human.searchCatalog)
    }

    @Test
    fun `english current reference rejection accepts common anymore suffixes`() {
        listOf(
            "I don't want this clinic anymore" to "INSTITUTION",
            "I don't want this doctor any more" to "DOCTOR",
            "I don't want this clinic any longer because I changed my mind" to "INSTITUTION"
        ).forEach { (query, contextType) ->
            val current = router.assessCurrent(query, contextType)

            assertTrue("NEGATED_CURRENT_REFERENCE" in current.ambiguityReasons, query)
            assertFalse("UNRESOLVED_CURRENT_REFERENCE" in current.ambiguityReasons, query)
            val completed = router.decide(query, contextType)
            assertEquals(AgentIntent.GENERAL_CHAT, completed.intent, query)
            assertEquals(null, completed.queryTarget, query)
        }
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
    fun `context resolves only uncertain doctor without changing locked comparison institution`() {
        val result = router.supplementWithContext(
            router.assessCurrent("I do not not want a doctor; compare clinics", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
        assertTrue(result.evidenceFor(AgentIntent.COMPARISON).locked)
        assertTrue(result.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertEquals(AgentLabelSource.CONTEXT, result.evidenceFor(AgentQueryTarget.DOCTOR).source)
        assertFalse(result.evidenceFor(AgentQueryTarget.DOCTOR).locked)
    }

    @Test
    fun `context fills absent target without deleting current intent labels`() {
        val result = router.supplementWithContext(
            router.assessCurrent("Compare options and plan around my budget", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.DOCTOR))
        )

        assertEquals(AgentIntent.COMPARISON, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertEquals(setOf(AgentIntent.COMPARISON, AgentIntent.PLANNING, AgentIntent.CATALOG_QA), result.positiveIntents())
        assertEquals(setOf(AgentQueryTarget.DOCTOR), result.positiveTargets())
        assertEquals(AgentLabelSource.CONTEXT, result.evidenceFor(AgentQueryTarget.DOCTOR).source)
        assertFalse(result.evidenceFor(AgentQueryTarget.DOCTOR).locked)
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
    fun `deterministic context follow up rejects parser intent changes except safety`() {
        val local = router.supplementWithContext(
            router.assessCurrent("多少钱？", "GENERAL"),
            listOf(router.validatedDecision(AgentIntent.CATALOG_QA, AgentQueryTarget.INSTITUTION))
        )

        assertTrue(local.contextResolvedQueryTarget)
        listOf(
            ParsedAgentRoute(AgentIntent.HUMAN_CONSULTATION, AgentQueryTarget.INSTITUTION, emptyList()),
            ParsedAgentRoute(AgentIntent.DETAIL_SUMMARY, AgentQueryTarget.INSTITUTION, emptyList()),
            ParsedAgentRoute(
                AgentIntent.CATALOG_QA,
                AgentQueryTarget.INSTITUTION,
                emptyList(),
                intents = setOf(AgentIntent.DETAIL_SUMMARY)
            )
        ).forEach { parsed ->
            val result = router.mergeParsedRoute(local, parsed)
            assertEquals(AgentIntent.CATALOG_QA, result.intent, parsed.toString())
            assertEquals(AgentQueryTarget.INSTITUTION, result.queryTarget, parsed.toString())
        }

        val safety = router.mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.SAFETY_SCREENING, null, emptyList())
        )
        assertEquals(AgentIntent.SAFETY_SCREENING, safety.intent)
        assertEquals(null, safety.queryTarget)
    }

    @Test
    fun `bare deictic context may upgrade to human consultation across detail targets`() {
        listOf(
            AgentQueryTarget.DOCTOR,
            AgentQueryTarget.PROJECT,
            AgentQueryTarget.INSTITUTION_PROJECT
        ).forEach { contextTarget ->
            val local = router.supplementWithContext(
                router.assessCurrent("Could someone help me with this?", "GENERAL"),
                listOf(router.validatedDecision(AgentIntent.CATALOG_QA, contextTarget))
            )

            val result = router.mergeParsedRoute(
                local,
                ParsedAgentRoute(AgentIntent.HUMAN_CONSULTATION, null, emptyList())
            )

            assertTrue(local.contextResolvedQueryTarget, contextTarget.toString())
            assertEquals(AgentIntent.HUMAN_CONSULTATION, result.intent, contextTarget.toString())
            assertEquals(AgentQueryTarget.INSTITUTION, result.queryTarget, contextTarget.toString())
        }
    }

    @Test
    fun `parser optional human label cannot override an explicit business intent`() {
        val local = router.assessCurrent("对比一下", "GENERAL")
        assertTrue(local.explicitIntent)

        val result = router.mergeParsedRoute(
            local,
            ParsedAgentRoute(
                intent = AgentIntent.COMPARISON,
                queryTarget = AgentQueryTarget.INSTITUTION,
                keywords = emptyList(),
                intents = setOf(AgentIntent.HUMAN_CONSULTATION)
            )
        )

        assertEquals(AgentIntent.COMPARISON, result.intent)
        assertEquals(null, result.queryTarget)
    }

    @Test
    fun `conflicting current targets retain a locked primary while requesting parsing`() {
        val local = router.assessCurrent("对比医生和机构", "GENERAL")

        val result = router.mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.COMPARISON, AgentQueryTarget.INSTITUTION, emptyList())
        )

        assertTrue(local.evidenceFor(AgentQueryTarget.DOCTOR).locked)
        assertTrue(local.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
        assertTrue(local.explicitQueryTarget)
        assertTrue("CONFLICTING_CURRENT_TARGETS" in local.ambiguityReasons)
        assertTrue(local.requiresLlmParsing)
        assertEquals(AgentQueryTarget.DOCTOR, result.queryTarget)
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

    @Test
    fun `clause boundary keeps pregnancy positive after negated comparison`() {
        val result = router.assessCurrent("Don't compare treatments while pregnant", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertTrue(result.evidenceFor(AgentIntent.SAFETY_SCREENING).locked)
    }

    @Test
    fun `english safety uncertainty requires parser fallback`() {
        val result = router.assessCurrent("I don't know if I am pregnant", "GENERAL")

        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertTrue(result.requiresLlmParsing)
    }

    @Test
    fun `state negation leaves later comparison and project labels locked`() {
        val result = router.assessCurrent("I am not pregnant; compare treatments", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertTrue(result.evidenceFor(AgentIntent.COMPARISON).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.PROJECT).locked)
    }

    @Test
    fun `coordinated safety states share a bounded negation`() {
        listOf(
            "我没有怀孕或哺乳，想比较项目",
            "I am not pregnant or breastfeeding; compare treatments"
        ).forEach { query ->
            val result = router.assessCurrent(query, "GENERAL")

            assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
            assertEquals(AgentIntent.COMPARISON, result.decision.intent)
            assertEquals(AgentQueryTarget.PROJECT, result.decision.queryTarget)
        }
    }

    @Test
    fun `coordinated business actions share a bounded negation`() {
        listOf(
            "不要比较或规划项目",
            "Don't compare or plan treatments"
        ).forEach { query ->
            val result = router.assessCurrent(query, "GENERAL")

            assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
            assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.PLANNING).polarity)
            assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
            assertFalse(result.decision.searchCatalog)
        }
    }

    @Test
    fun `chinese clause boundary keeps pregnancy positive after negated comparison`() {
        val result = router.assessCurrent("不要比较项目，同时我怀孕了", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertTrue(result.evidenceFor(AgentIntent.SAFETY_SCREENING).locked)
    }

    @Test
    fun `chinese safety uncertainty retains comparison and institution labels`() {
        val result = router.assessCurrent("我不确定是否怀孕，想比较机构", "GENERAL")

        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertTrue(result.evidenceFor(AgentIntent.COMPARISON).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
    }

    @Test
    fun `plural institutions are a positive institution target`() {
        val result = router.assessCurrent("Compare institutions", "GENERAL")

        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
    }

    @Test
    fun `plural procedures are a positive project target`() {
        val result = router.assessCurrent("What procedures help acne?", "GENERAL")

        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
    }

    @Test
    fun `hospitality is not an institution target`() {
        val result = router.assessCurrent("Tell me about hospitality", "GENERAL")

        assertTrue(result.targetEvidence.none { it.target == AgentQueryTarget.INSTITUTION })
    }

    @Test
    fun `ambiguous compatible targets become uncertain without affecting later labels`() {
        val result = router.assessCurrent("不要机构项目，推荐医生", "GENERAL")

        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentQueryTarget.INSTITUTION_PROJECT).polarity)
        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.DOCTOR).locked)
    }

    @Test
    fun `negated english comparison retains treatment evidence without catalog primary route`() {
        val result = router.assessCurrent("Don't compare treatments", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertFalse(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
    }

    @Test
    fun `negated chinese comparison retains project evidence without catalog primary route`() {
        val result = router.assessCurrent("不要比较项目", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertFalse(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
    }

    @Test
    fun `negated english catalog action retains clinic evidence without positive recommendation route`() {
        val result = router.assessCurrent("Don't recommend clinics", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.CATALOG_QA).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertFalse(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
    }

    @Test
    fun `negated chinese catalog action retains institution evidence without positive recommendation route`() {
        val result = router.assessCurrent("不要推荐机构", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.CATALOG_QA).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertFalse(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
    }

    @Test
    fun `double negated comparison remains uncertain and does not promote attached treatments`() {
        val result = router.assessCurrent("I do not not compare treatments", "GENERAL")

        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertFalse(result.evidenceFor(AgentIntent.COMPARISON).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertEquals(AgentIntent.GENERAL_CHAT, result.decision.intent)
        assertEquals(null, result.decision.queryTarget)
        assertFalse(result.explicitIntent)
        assertFalse(result.explicitQueryTarget)
    }

    @Test
    fun `double negated comparison leaves safety and independent clinic labels locked`() {
        val result = router.assessCurrent("I do not not compare treatments; I am pregnant; show clinics", "GENERAL")

        assertEquals(AgentLabelPolarity.UNCERTAIN, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertFalse(result.evidenceFor(AgentIntent.COMPARISON).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentIntent.SAFETY_SCREENING).polarity)
        assertTrue(result.evidenceFor(AgentIntent.SAFETY_SCREENING).locked)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.INSTITUTION).locked)
        assertEquals(AgentQueryTarget.INSTITUTION, result.decision.queryTarget)
    }

    @Test
    fun `later english recommendation keeps doctors independent from negated comparison`() {
        val result = router.assessCurrent("Don't compare treatments and recommend doctors", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.DOCTOR).locked)
        assertEquals(AgentIntent.CATALOG_QA, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertTrue(result.explicitQueryTarget)
    }

    @Test
    fun `later chinese recommendation keeps doctors independent from negated comparison`() {
        val result = router.assessCurrent("不要比较项目并推荐医生", "GENERAL")

        assertEquals(AgentLabelPolarity.NEGATIVE, result.evidenceFor(AgentIntent.COMPARISON).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.PROJECT).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertTrue(result.evidenceFor(AgentQueryTarget.DOCTOR).locked)
        assertEquals(AgentIntent.CATALOG_QA, result.decision.intent)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertTrue(result.explicitQueryTarget)
    }

    @Test
    fun `attached english clinic package does not suppress independent target conflict`() {
        val result = router.assessCurrent("Don't compare clinic packages; recommend doctors and clinics", "GENERAL")

        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION_PROJECT).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertTrue("CONFLICTING_CURRENT_TARGETS" in result.ambiguityReasons)
        assertTrue(result.requiresLlmParsing)
    }

    @Test
    fun `attached chinese institution package does not suppress independent target conflict`() {
        val result = router.assessCurrent("不要比较机构套餐；推荐医生和机构", "GENERAL")

        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION_PROJECT).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.DOCTOR).polarity)
        assertEquals(AgentLabelPolarity.POSITIVE, result.evidenceFor(AgentQueryTarget.INSTITUTION).polarity)
        assertEquals(AgentQueryTarget.DOCTOR, result.decision.queryTarget)
        assertTrue("CONFLICTING_CURRENT_TARGETS" in result.ambiguityReasons)
        assertTrue(result.requiresLlmParsing)
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

    private fun AgentRouteAssessment.positiveIntents(): Set<AgentIntent> = intentEvidence
        .filter { it.polarity == AgentLabelPolarity.POSITIVE }
        .mapTo(mutableSetOf()) { it.intent }

    private fun AgentRouteAssessment.positiveTargets(): Set<AgentQueryTarget> = targetEvidence
        .filter { it.polarity == AgentLabelPolarity.POSITIVE }
        .mapTo(mutableSetOf()) { it.target }

    private fun AgentRouteAssessment.evidenceFor(intent: AgentIntent): AgentIntentEvidence =
        intentEvidence.single { it.intent == intent }

    private fun AgentRouteAssessment.evidenceFor(target: AgentQueryTarget): AgentTargetEvidence =
        targetEvidence.single { it.target == target }

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
