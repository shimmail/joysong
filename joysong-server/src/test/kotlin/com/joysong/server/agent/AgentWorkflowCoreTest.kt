package com.joysong.server.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.context.AgentContext
import com.joysong.server.agent.context.AgentSessionSummary
import com.joysong.server.agent.context.ConversationFocusUpdate
import com.joysong.server.agent.diagnostics.AgentOperationLogger
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.dto.AgentProfileResponse
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.AiAgentAvailabilityGuard
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.AgentMessageProjection
import com.joysong.server.agent.orchestration.IdempotencyKeyConflictException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.agent.service.AgentCatalogService
import com.joysong.server.agent.service.ConsultableInstitutionSelection
import com.joysong.server.agent.service.AgentIntent
import com.joysong.server.agent.service.AgentIntentRouter
import com.joysong.server.agent.service.AgentProfileService
import com.joysong.server.agent.service.AgentRouteAssessment
import com.joysong.server.agent.service.ParsedAgentRoute
import com.joysong.server.agent.service.AgentPromptEvidence
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.ComparisonMissingField
import com.joysong.server.agent.service.ComparisonOperand
import com.joysong.server.agent.service.ComparisonRequest
import com.joysong.server.agent.service.ComparisonRequestBuilder
import com.joysong.server.agent.streaming.AgentStreamEvent
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatService
import com.joysong.server.chat.service.ChatTurnResult
import com.joysong.server.chat.service.PreparedChatTurn
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.config.AiAgentProvider
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.service.DiscoverKeywordExtractor
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestTemplate
import java.net.SocketTimeoutException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class AgentWorkflowCoreTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val messages = mockk<ChatMessageRepository>()
    private val turns = mockk<AgentTurnRepository>()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val clock = Clock.fixed(Instant.parse("2026-08-10T04:00:00Z"), ZoneOffset.UTC)
    private val context = AgentContextBuilder(sessions, messages, turns, objectMapper, clock, 20, 7)
    private val turnLease = Duration.ofMinutes(2)
    private val lifecycle = TurnLifecycleService(
        sessions,
        messages,
        turns,
        context,
        objectMapper,
        clock = clock,
        turnLease = turnLease,
        comparisonRequestBuilder = ComparisonRequestBuilder()
    )

    @BeforeEach
    fun defaultMessageCleanupCandidates() {
        every { messages.findBySessionIdOrderBySequenceNoAsc(any()) } returns emptyList()
    }

    @Test
    fun `current Chinese message overrides English conversation history for generation language`() {
        val content = "Ultherapy多少钱"
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(RestTemplate(), RestTemplate(), catalog)
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(),
            listOf(
                message(1, "USER", "Please introduce yourself."),
                message(2, "ASSISTANT", "I am your medical aesthetics assistant.")
            )
        )
        every { messages.findByTurnIdAndRole("turn-1", "USER") } returns null
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, listOf("Please introduce yourself.")) } returns content
        every { catalog.promptEvidence(any(), any(), any(), any(), any(), any()) } returns AgentPromptEvidence()

        val prepared = fixture.chat.prepareStreamingMessage(
            "session-1",
            "user-1",
            SendMessageRequest(content = content)
        ) as PreparedChatTurn.Started

        assertEquals(
            mapOf(
                "role" to "system",
                "content" to "本轮语言要求：当前用户消息使用中文。只用中文回答，不要附加英文翻译，即使历史消息使用英文。"
            ),
            prepared.messages.last()
        )
        assertTrue(prepared.messages.indexOf(mapOf("role" to "user", "content" to content)) < prepared.messages.lastIndex)
    }

    @Test
    fun `current English message overrides Chinese conversation history for generation language`() {
        val content = "What is 超声炮?"
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(RestTemplate(), RestTemplate(), catalog)
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(),
            listOf(
                message(1, "USER", "请介绍一下你自己"),
                message(2, "ASSISTANT", "我是你的医美咨询助手。")
            )
        )
        every { messages.findByTurnIdAndRole("turn-1", "USER") } returns null
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, listOf("请介绍一下你自己")) } returns content
        every { catalog.promptEvidence(any(), any(), any(), any(), any(), any()) } returns AgentPromptEvidence()

        val prepared = fixture.chat.prepareStreamingMessage(
            "session-1",
            "user-1",
            SendMessageRequest(content = content)
        ) as PreparedChatTurn.Started

        assertEquals(
            mapOf(
                "role" to "system",
                "content" to "Turn language requirement: The current user message is in English. Answer in English only. Do not add a Chinese translation, even if earlier messages are in Chinese."
            ),
            prepared.messages.last()
        )
        assertTrue(prepared.messages.indexOf(mapOf("role" to "user", "content" to content)) < prepared.messages.lastIndex)
    }

    @Test
    fun `stream events serialize with stable public field names`() {
        val mapper = jacksonObjectMapper()
        val failed = mapper.readTree(
            mapper.writeValueAsString(AgentStreamEvent.Failed("AI_PROVIDER_TIMEOUT", "trace-1", true))
        )

        assertEquals("AI_PROVIDER_TIMEOUT", failed.path("code").asText())
        assertEquals("trace-1", failed.path("traceId").asText())
        assertTrue(failed.path("retryable").asBoolean())
    }

    @Test
    fun `ambiguous route uses intent model while answer uses main model`() {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val turnService = mockk<TurnLifecycleService>()
        val operationLogger = mockk<AgentOperationLogger>()
        val contextBuilder = mockk<AgentContextBuilder>()
        val availabilityGuard = mockk<AiAgentAvailabilityGuard>()
        val chat = ChatService(
            sessionRepository = sessions,
            messageRepository = messages,
            projectRepository = mockk<ProjectRepository>(),
            institutionRepository = mockk<InstitutionRepository>(),
            institutionProjectRepository = mockk<InstitutionProjectRepository>(),
            doctorRepository = mockk<DoctorRepository>(),
            doctorInstitutionService = mockk<DoctorInstitutionService>(),
            institutionProjectDetailResolver = mockk<InstitutionProjectDetailResolver>(),
            agentCatalogService = catalog,
            agentIntentRouter = AgentIntentRouter(),
            comparisonRequestBuilder = ComparisonRequestBuilder(),
            turnLifecycleService = turnService,
            agentOperationLogger = operationLogger,
            agentContextBuilder = contextBuilder,
            aiAgentAvailabilityGuard = availabilityGuard,
            objectMapper = objectMapper,
            restTemplate = completionTemplate,
            intentParserRestTemplate = intentTemplate,
            aiAgentProperties = AiAgentProperties(
                provider = AiAgentProvider.OPENAI_COMPATIBLE,
                apiKey = "test-key",
                baseUrl = "https://provider.test/v1",
                model = "answer-model",
                intentModel = "intent-small"
            )
        )
        every { availabilityGuard.requireGenerationEnabled() } just runs
        every { turnService.beginTurn("session-1", "user-1", "我想改善脸部松弛", any()) } returns
            BeginTurnResult.Started("turn-1", "trace-1", 1)
        every { turnService.completeTurn(any()) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { operationLogger.completed(any(), any(), any(), any(), any()) } just runs
        every { contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(AgentSessionSummary(), emptyList())
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-1") } returns session()
        every { catalog.hasInstitutionProjectMatch("我想改善脸部松弛") } returns false
        every { catalog.contextualSearchQuery("我想改善脸部松弛", emptyList()) } returns "我想改善脸部松弛"
        every {
            catalog.promptEvidence(
                "我想改善脸部松弛",
                "我想改善脸部松弛 fixture-keyword",
                "我想改善脸部松弛 fixture-keyword",
                AgentQueryTarget.PROJECT,
                "AUTO",
                "我想改善脸部松弛"
            )
        } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"model":"intent-small"}""", false))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"PROJECT\",\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"model":"answer-model"}""", false))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        chat.sendMessage("session-1", "user-1", SendMessageRequest(content = "我想改善脸部松弛"))

        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `clear human consultation persists fixed localized response without either model`() {
        val chineseContent = "我想转人工咨询"
        val institutionItem = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = "institution-1",
            name = "安心医美",
            subtitle = "Shanghai",
            summary = "",
            attributes = emptyMap(),
            institutionId = "institution-1",
            canChatWithHuman = true
        )
        val selection = ConsultableInstitutionSelection(listOf(institutionItem), false)
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, chineseContent)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
        )
        every { catalog.selectConsultableInstitutions("user-1", chineseContent, null) } returns selection

        val previousLocale = LocaleContextHolder.getLocale()
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        try {
            fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = chineseContent))

            assertEquals("我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。", completed.captured.content)
            assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
            assertEquals("INSTITUTION", completed.captured.queryTarget)
            assertEquals("SELECT_INSTITUTION", completed.captured.nextAction)
            assertEquals(institutionItem, completed.captured.catalogItems.single())
            assertEquals("", completed.captured.modelName)
            verify(exactly = 0) {
                catalog.contextualSearchQuery(any(), any())
                catalog.promptEvidence(any(), any(), any(), any(), any(), any())
            }
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
        intentServer.verify()
        completionServer.verify()

        val englishContent = "I want to speak to a person"
        val streamingCompletionTemplate = RestTemplate()
        val streamingIntentTemplate = RestTemplate()
        val streamingCompletionServer = MockRestServiceServer.bindTo(streamingCompletionTemplate).build()
        val streamingIntentServer = MockRestServiceServer.bindTo(streamingIntentTemplate).build()
        val streamingCatalog = mockk<AgentCatalogService>()
        val streamingFixture = chatFixture(streamingCompletionTemplate, streamingIntentTemplate, streamingCatalog)
        val streamingCompleted = slot<CompleteTurnCommand>()
        prepareChatGeneration(streamingFixture, englishContent)
        every { streamingFixture.turnService.completeTurn(capture(streamingCompleted)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
        )
        every { messages.findByTurnIdAndRole("turn-1", "USER") } returns null
        every { streamingCatalog.selectConsultableInstitutions("user-1", englishContent, null) } returns selection

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)
        try {
            val prepared = streamingFixture.chat.prepareStreamingMessage(
                "session-1",
                "user-1",
                SendMessageRequest(content = englishContent)
            )

            assertTrue(prepared is PreparedChatTurn.Completed)
            assertEquals(
                "I can help connect you with a real consultant. Choose an institution to see its currently available consultants.",
                streamingCompleted.captured.content
            )
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
        streamingIntentServer.verify()
        streamingCompletionServer.verify()
    }

    @Test
    fun `bilingual deictic human consultation keeps detail context without answer model`() {
        val contextInstitution = InstitutionEntity(
            id = "context",
            name = "杭州安心",
            city = "杭州",
            rating = BigDecimal("4.1"),
            isVerified = true
        )
        val alternatives = (1..4).map { index ->
            InstitutionEntity(
                id = "alternative-$index",
                name = "上海优选$index",
                city = "上海",
                rating = BigDecimal("4.${10 - index}"),
                isVerified = true
            )
        }
        val institutions = listOf(contextInstitution) + alternatives
        val institutionRepository = mockk<InstitutionRepository>()
        val doctorRepository = mockk<DoctorRepository>(relaxed = true)
        val projectRepository = mockk<ProjectRepository>(relaxed = true)
        val institutionProjectRepository = mockk<InstitutionProjectRepository>(relaxed = true)
        val doctorProjectRepository = mockk<DoctorProjectRepository>(relaxed = true)
        val doctorInstitutionService = mockk<DoctorInstitutionService>(relaxed = true)
        val institutionConsultantService = mockk<InstitutionConsultantService>()
        val agentProfileService = mockk<AgentProfileService>()
        every { institutionRepository.findAll() } returns institutions
        every { institutionRepository.countSoftDeletedNamesMentionedInQuery(any()) } returns 0
        every { institutionConsultantService.listConsultableInstitutionIds() } returns institutions.map { it.id }.toSet()
        every { agentProfileService.get("user-1") } returns AgentProfileResponse(
            id = null,
            city = "上海",
            goals = emptyList(),
            budgetMin = null,
            budgetMax = null,
            acceptableDowntimeDays = null,
            painTolerance = "",
            preferences = emptyList(),
            excludedProjects = emptyList(),
            consentVersion = "",
            confirmedAt = null,
            completenessScore = 0,
            missingFields = emptyList()
        )
        val discoverSearchService = DiscoverSearchService(
            projectRepository = projectRepository,
            institutionRepository = institutionRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorRepository = doctorRepository,
            doctorProjectRepository = doctorProjectRepository,
            keywordExtractor = DiscoverKeywordExtractor(),
            doctorInstitutionService = doctorInstitutionService,
            institutionProjectDetailResolver = InstitutionProjectDetailResolver()
        )
        val catalog = AgentCatalogService(
            institutionRepository = institutionRepository,
            doctorRepository = doctorRepository,
            projectRepository = projectRepository,
            institutionProjectRepository = institutionProjectRepository,
            doctorProjectRepository = doctorProjectRepository,
            discoverSearchService = discoverSearchService,
            doctorInstitutionService = doctorInstitutionService,
            institutionProjectDetailResolver = InstitutionProjectDetailResolver(),
            institutionConsultantService = institutionConsultantService,
            agentProfileService = agentProfileService
        )
        val cases = listOf(
            Triple(
                "我想咨询这家诊所的真人顾问",
                Locale.SIMPLIFIED_CHINESE,
                "我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。"
            ),
            Triple(
                "talk to a specialist at that clinic",
                Locale.ENGLISH,
                "I can help connect you with a real consultant. Choose an institution to see its currently available consultants."
            )
        )
        val previousLocale = LocaleContextHolder.getLocale()
        try {
            cases.forEach { (content, locale, expectedCopy) ->
                val completionTemplate = RestTemplate()
                val intentTemplate = RestTemplate()
                val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
                val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
                intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                    .andRespond(withSuccess(
                        """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                        MediaType.APPLICATION_JSON
                    ))
                val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
                val completed = slot<CompleteTurnCommand>()
                prepareChatGeneration(
                    fixture,
                    content,
                    session(contextType = "INSTITUTION", contextId = contextInstitution.id)
                )
                every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
                    ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
                )
                LocaleContextHolder.setLocale(locale)

                fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

                assertEquals(expectedCopy, completed.captured.content)
                assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
                assertEquals("INSTITUTION", completed.captured.queryTarget)
                assertEquals("SELECT_INSTITUTION", completed.captured.nextAction)
                assertEquals(contextInstitution.id, completed.captured.catalogItems.first().id)
                assertEquals("", completed.captured.modelName)
                intentServer.verify()
                completionServer.verify()
            }
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
    }

    @Test
    fun `unrelated general chat after detail entry drops the context prompt and card`() {
        val content = "换个话题，聊聊价格"
        val test = detailEntryChatFixture(content, "旧机构不应进入本轮", "价格闲聊")
        every {
            test.catalog.promptEvidence(
                content,
                content,
                content,
                any(),
                "AUTO",
                content
            )
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格闲聊"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("GENERAL_CHAT", test.completed.captured.intent)
        assertEquals(null, test.completed.captured.queryTarget)
        assertEquals("NONE", test.completed.captured.nextAction)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        assertEquals(ConversationFocusUpdate.CLEAR, test.completed.captured.conversationFocusUpdate)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `social interjection hides the entry card without releasing the detail context`() {
        val content = "收到"
        val test = detailEntryChatFixture(content, "寒暄后仍可继续的机构", "不客气")
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString("CATALOG_QA/INSTITUTION"))))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"GENERAL_CHAT\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"不客气"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("GENERAL_CHAT", test.completed.captured.intent)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertFalse(test.completed.captured.releaseDetailContext)
        assertEquals(ConversationFocusUpdate.PRESERVE, test.completed.captured.conversationFocusUpdate)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `social interjection does not supersede detail context for the next implicit question`() {
        val content = "多少钱？"
        val priorTurnId = "social-turn"
        val priorUser = message(1, "USER", "好的").apply { turnId = priorTurnId }
        val priorAssistant = message(2, "ASSISTANT", "不客气").apply {
            turnId = priorTurnId
            metadataJson = objectMapper.writeValueAsString(
                linkedMapOf(
                    "intent" to "GENERAL_CHAT",
                    "queryTarget" to null,
                    "nextAction" to "NONE",
                    "conversationFocusUpdate" to "PRESERVE",
                    "catalogItems" to emptyList<AgentCatalogItemResponse>(),
                    "catalogReport" to null
                )
            )
        }
        val test = detailEntryChatFixture(
            content,
            "寒暄后仍应恢复的机构",
            "价格回答",
            historyMessages = listOf(priorUser, priorAssistant),
            summary = AgentSessionSummary(
                focusSemanticsVersion = 1,
                unresolvedTopics = listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
                lastSummarizedSequence = 1
            )
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `legacy social metadata does not supersede detail context for the next implicit question`() {
        val content = "多少钱？"
        val priorTurnId = "legacy-social-turn"
        val priorUser = message(1, "USER", "好的").apply { turnId = priorTurnId }
        val priorAssistant = message(2, "ASSISTANT", "不客气").apply {
            turnId = priorTurnId
            metadataJson = "{}"
        }
        val test = detailEntryChatFixture(
            content,
            "旧版寒暄后仍应恢复的机构",
            "价格回答",
            historyMessages = listOf(priorUser, priorAssistant),
            summary = AgentSessionSummary(
                unresolvedTopics = listOf(
                    "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                    "GENERAL_CHAT"
                ),
                lastSummarizedSequence = 1
            )
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `legacy social window cannot revive an earlier explicit topic boundary`() {
        val content = "多少钱？"
        val history = listOf(
            "好的" to "不客气",
            "这家机构怎么样" to "机构回答",
            "换个话题，讲个笑话" to "笑话回答",
            "好的" to "不客气",
            "收到" to "没问题",
            "明白了" to "好的",
            "ok" to "You are welcome",
            "好的" to "不客气",
            "收到" to "没问题",
            "明白了" to "好的",
            "ok" to "You are welcome"
        ).flatMapIndexed { index, (userContent, assistantContent) ->
            val turnId = "legacy-boundary-turn-${index + 1}"
            listOf(
                message(index * 2L + 1, "USER", userContent).apply { this.turnId = turnId },
                message(index * 2L + 2, "ASSISTANT", assistantContent).apply {
                    this.turnId = turnId
                    metadataJson = "{}"
                }
            )
        }.drop(2)
        val summary = AgentSessionSummary(
            unresolvedTopics = listOf(
                "GENERAL_CHAT",
                "CATALOG_QA:INSTITUTION:SHOW_CATALOG"
            ),
            lastSummarizedSequence = 11
        )
        val test = detailEntryChatFixture(
            content,
            "真实换题后不能复活的旧机构",
            "普通回答",
            historyMessages = history,
            summary = summary
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), null, any(), any())
        } returns AgentPromptEvidence()
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"普通回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, test.completed.captured.queryTarget)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `incomplete legacy history cannot revive a replaced same target detail`() {
        val content = "多少钱？"
        val history = List(10) { index ->
            if (index % 2 == 0) "好的" to "不客气" else "收到" to "没问题"
        }.flatMapIndexed { index, (userContent, assistantContent) ->
            val turnId = "legacy-truncated-turn-${index + 2}"
            listOf(
                message(index * 2L + 3, "USER", userContent).apply { this.turnId = turnId },
                message(index * 2L + 4, "ASSISTANT", assistantContent).apply {
                    this.turnId = turnId
                    metadataJson = "{}"
                }
            )
        }
        val summary = AgentSessionSummary(
            unresolvedTopics = listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
            lastSummarizedSequence = 11
        )
        val test = detailEntryChatFixture(
            content,
            "已被同类型替换且不能复活的旧机构",
            "普通回答",
            historyMessages = history,
            summary = summary
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), null, any(), any())
        } returns AgentPromptEvidence()
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"普通回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, test.completed.captured.queryTarget)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `same target attribute question keeps the entry prompt and card`() {
        val content = "机构有哪些优势"
        val test = detailEntryChatFixture(content, "当前优势机构", "优势回答")
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString(test.institution.name)))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"优势回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `suspended doctor detail uses public lookups and omits the current card`() {
        val content = "这位医生有哪些优势"
        val doctorRepository = mockk<DoctorRepository>()
        every { doctorRepository.findPublicById("suspended-doctor") } returns java.util.Optional.empty()
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            doctorRepository = doctorRepository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(contextType = "DOCTOR", contextId = "suspended-doctor")
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "优势回答")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.DOCTOR, "AUTO", content)
        } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"DOCTOR\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"优势回答"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(emptyList<AgentCatalogItemResponse>(), completed.captured.catalogItems)
        verify(exactly = 2) { doctorRepository.findPublicById("suspended-doctor") }
        verify(exactly = 0) { doctorRepository.findById("suspended-doctor") }
        completionServer.verify()
    }

    @Test
    fun `price confirmation keeps the positively referenced entry detail`() {
        listOf(
            "Isn't this clinic more expensive?",
            "Isn't this clinic more expensive than the other one?",
            "Isn't this clinic cheaper than that one?"
        ).forEachIndexed { index, content ->
            val test = detailEntryChatFixture(content, "当前价格机构-$index", "价格确认回答")
            every {
                test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
            } returns AgentPromptEvidence()
            test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(
                    """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                    MediaType.APPLICATION_JSON
                ))
            test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(content().string(containsString(test.institution.name)))
                .andRespond(withSuccess(
                    """{"choices":[{"message":{"content":"价格确认回答"}}]}""",
                    MediaType.APPLICATION_JSON
                ))

            test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

            assertEquals("CATALOG_QA", test.completed.captured.intent, content)
            assertEquals("INSTITUTION", test.completed.captured.queryTarget, content)
            assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id }, content)
            assertFalse(test.completed.captured.releaseDetailContext, content)
            test.intentServer.verify()
            test.completionServer.verify()
        }
    }

    @Test
    fun `switching catalog target after detail entry shows new target cards only`() {
        val content = "换个话题，推荐几位医生"
        val test = detailEntryChatFixture(content, "旧机构不应覆盖医生结果", "医生回答")
        val doctorItem = AgentCatalogItemResponse(
            type = "DOCTOR",
            id = "doctor-2",
            name = "新话题医生",
            subtitle = "主任医师",
            summary = "",
            attributes = emptyMap()
        )
        val evidence = singleItemEvidence("医生", doctorItem)
        every {
            test.catalog.promptEvidence(
                content,
                content,
                content,
                AgentQueryTarget.DOCTOR,
                "AUTO",
                content
            )
        } returns evidence
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"医生回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("DOCTOR", test.completed.captured.queryTarget)
        assertEquals(listOf(doctorItem), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        assertEquals(ConversationFocusUpdate.SET, test.completed.captured.conversationFocusUpdate)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `explicit topic switch to the same catalog type excludes the entry card`() {
        val content = "换个话题，推荐机构"
        val test = detailEntryChatFixture(content, "同类换题不应复活的旧机构", "机构回答")
        val entry = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = test.institution.id,
            name = test.institution.name,
            subtitle = test.institution.city,
            summary = "",
            attributes = emptyMap(),
            institutionId = test.institution.id
        )
        val alternative = entry.copy(
            id = "institution-new",
            name = "新话题机构",
            institutionId = "institution-new"
        )
        every {
            test.catalog.promptEvidence(
                content,
                content,
                content,
                AgentQueryTarget.INSTITUTION,
                "AUTO",
                content
            )
        } returns AgentPromptEvidence(
            context = "${entry.name}; ${alternative.name}",
            report = AgentCatalogReportResponse(
                mode = "AUTO",
                title = "机构",
                summary = "",
                items = listOf(entry, alternative),
                comparisonDimensions = emptyList(),
                warnings = emptyList()
            )
        )
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"机构回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(alternative), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `explicit planning topic switch does not reuse the entry prompt or card`() {
        val content = "换个话题，预算5000帮我规划"
        val test = detailEntryChatFixture(content, "规划换题不应复活的旧机构", "规划回答")
        every {
            test.catalog.promptEvidence(any(), any(), any(), null, "PLANNING", any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"规划回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("PLANNING", test.completed.captured.intent)
        assertEquals(null, test.completed.captured.queryTarget)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `deictic cross target request keeps the institution as search scope without its card`() {
        val content = "这家机构还有其他医生吗"
        val test = detailEntryChatFixture(content, "当前检索范围机构", "医生回答")
        val doctor = AgentCatalogItemResponse(
            type = "DOCTOR",
            id = "doctor-2",
            name = "本机构其他医生",
            subtitle = "主任医师",
            summary = "",
            attributes = emptyMap(),
            institutionId = test.institution.id
        )
        val scopedQuery = "$content ${test.institution.name}"
        every {
            test.catalog.promptEvidence(
                content,
                scopedQuery,
                scopedQuery,
                AgentQueryTarget.DOCTOR,
                "AUTO",
                content
            )
        } returns singleItemEvidence("医生", doctor)
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"医生回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("DOCTOR", test.completed.captured.queryTarget)
        assertEquals(listOf(doctor), test.completed.captured.catalogItems)
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `deictic institution scope selects projects instead of the scoped institution`() {
        val content = "这家机构还有其他项目吗"
        val test = detailEntryChatFixture(content, "当前项目检索范围机构", "项目回答")
        val project = AgentCatalogItemResponse(
            type = "PROJECT",
            id = "project-2",
            name = "本机构其他项目",
            subtitle = "皮肤管理",
            summary = "",
            attributes = emptyMap()
        )
        val scopedQuery = "$content ${test.institution.name}"
        every {
            test.catalog.promptEvidence(
                content,
                scopedQuery,
                scopedQuery,
                AgentQueryTarget.PROJECT,
                "AUTO",
                content
            )
        } returns singleItemEvidence("项目", project)
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"项目回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("PROJECT", test.completed.captured.queryTarget)
        assertEquals(listOf(project), test.completed.captured.catalogItems)
        assertFalse(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `same type alternatives drop the entry card across natural phrasings`() {
        listOf("推荐其他机构", "推荐另外几家", "介绍其他机构", "换一家", "换机构").forEachIndexed { index, content ->
            val test = detailEntryChatFixture(content, "旧机构不应覆盖同类结果-$index", "机构回答")
            val alternative = AgentCatalogItemResponse(
                type = "INSTITUTION",
                id = "institution-$index",
                name = "新话题机构-$index",
                subtitle = "杭州",
                summary = "",
                attributes = emptyMap()
            )
            val entry = AgentCatalogItemResponse(
                type = "INSTITUTION",
                id = test.institution.id,
                name = test.institution.name,
                subtitle = test.institution.city,
                summary = "",
                attributes = emptyMap()
            )
            val evidence = singleItemEvidence("机构", alternative).let {
                it.copy(
                    context = "${entry.name}; ${alternative.name}",
                    report = it.report!!.copy(items = listOf(entry, alternative))
                )
            }
            every {
                test.catalog.promptEvidence(
                    content,
                    content,
                    content,
                    AgentQueryTarget.INSTITUTION,
                    "AUTO",
                    content
                )
            } returns evidence
            test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(content().string(not(containsString(test.institution.name))))
                .andRespond(withSuccess("""{"choices":[{"message":{"content":"机构回答"}}]}""", MediaType.APPLICATION_JSON))

            test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

            assertEquals("CATALOG_QA", test.completed.captured.intent, content)
            assertEquals("INSTITUTION", test.completed.captured.queryTarget, content)
            assertEquals(listOf(alternative), test.completed.captured.catalogItems, content)
            assertTrue(test.completed.captured.releaseDetailContext, content)
            test.intentServer.verify()
            test.completionServer.verify()
        }
    }

    @Test
    fun `recent cross type switch resolves an implicit follow up without the parser`() {
        val content = "多少钱？"
        val priorQuery = "推荐几位医生"
        val test = detailEntryChatFixture(
            content,
            "旧机构不应在医生追问中复活",
            "价格回答",
            historyMessages = listOf(message(1, "USER", priorQuery))
        )
        val doctor = AgentCatalogItemResponse(
            type = "DOCTOR",
            id = "doctor-2",
            name = "新话题医生",
            subtitle = "主任医师",
            summary = "",
            attributes = emptyMap()
        )
        val evidence = singleItemEvidence("医生", doctor)
        every {
            test.catalog.promptEvidence(
                content,
                content,
                content,
                AgentQueryTarget.DOCTOR,
                "AUTO",
                content
            )
        } returns evidence
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("DOCTOR", test.completed.captured.queryTarget)
        assertEquals(listOf(doctor), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `recent same type switch keeps the entry card released on an implicit follow up`() {
        val content = "多少钱？"
        val priorQuery = "推荐其他机构"
        val test = detailEntryChatFixture(
            content,
            "旧机构不应在同类追问中复活",
            "价格回答",
            historyMessages = listOf(message(1, "USER", priorQuery))
        )
        val alternative = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = "institution-2",
            name = "新话题机构",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap()
        )
        val entry = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = test.institution.id,
            name = test.institution.name,
            subtitle = test.institution.city,
            summary = "",
            attributes = emptyMap(),
            institutionId = test.institution.id
        )
        val evidence = singleItemEvidence("机构", alternative).let {
            it.copy(
                context = "${entry.name}; ${alternative.name}",
                report = it.report!!.copy(items = listOf(entry, alternative))
            )
        }
        every {
            test.catalog.promptEvidence(
                content,
                content,
                content,
                AgentQueryTarget.INSTITUTION,
                "AUTO",
                content
            )
        } returns evidence
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(alternative), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `recent history overrides legacy summary ordering after detail context release`() {
        val content = "多少钱？"
        val history = listOf(message(1, "USER", "推荐医生"))
        val summaryTopics = listOf(
            "CATALOG_QA:DOCTOR:SHOW_CATALOG",
            "CATALOG_QA:INSTITUTION:SHOW_CATALOG"
        )
        val summaryJson = objectMapper.writeValueAsString(
            AgentSessionSummary(unresolvedTopics = summaryTopics)
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(summary = summaryJson))
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(unresolvedTopics = summaryTopics),
            history
        )
        every { fixture.contextBuilder.serializeSummary(any()) } returns summaryJson
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, listOf("推荐医生")) } returns content
        every { catalog.promptEvidence(any(), any(), any(), any(), any(), any()) } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"not-json"}}]}""", MediaType.APPLICATION_JSON))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals("DOCTOR", completed.captured.queryTarget)
        assertTrue(completed.captured.catalogItems.isEmpty())
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `explicit general topic boundary prevents older catalog focus from reviving`() {
        val content = "多少钱？"
        val summaryTopics = listOf("CATALOG_QA:DOCTOR:SHOW_CATALOG")
        val summaryJson = objectMapper.writeValueAsString(AgentSessionSummary(unresolvedTopics = summaryTopics))
        val history = listOf(
            message(1, "USER", "推荐医生"),
            message(2, "USER", "换个话题，讲个笑话")
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(summary = summaryJson))
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(unresolvedTopics = summaryTopics),
            history
        )
        every { fixture.contextBuilder.serializeSummary(any()) } returns summaryJson
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, history.map { it.content }) } returns content
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString("CATALOG_QA/DOCTOR"))))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"GENERAL_CHAT\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("GENERAL_CHAT", completed.captured.intent)
        assertEquals(null, completed.captured.queryTarget)
        assertTrue(completed.captured.catalogItems.isEmpty())
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `legacy summary boundary supersedes a stale persisted detail session without history`() {
        val content = "多少钱？"
        val institution = InstitutionEntity(
            id = "stale-institution",
            name = "摘要边界后不应复活的机构",
            city = "杭州",
            description = "旧详情",
            isVerified = true
        )
        val summaryTopics = listOf(
            "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
            "GENERAL_CHAT"
        )
        val summary = AgentSessionSummary(unresolvedTopics = summaryTopics)
        val summaryJson = objectMapper.writeValueAsString(summary)
        val institutionRepository = mockk<InstitutionRepository>()
        every { institutionRepository.findById(institution.id) } returns java.util.Optional.of(institution)
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionRepository = institutionRepository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(summary = summaryJson, contextType = "INSTITUTION", contextId = institution.id)
        )
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(summary, emptyList())
        every { fixture.contextBuilder.serializeSummary(summary) } returns summaryJson
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every { catalog.promptEvidence(any(), any(), any(), null, any(), any()) } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, completed.captured.queryTarget)
        assertTrue(completed.captured.catalogItems.isEmpty())
        assertTrue(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `legacy overlay without provenance cannot revive catalog focus in a general session`() {
        val content = "多少钱？"
        val summary = AgentSessionSummary(
            unresolvedTopics = listOf(
                "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                "SAFETY_SCREENING:COMPLETE_SAFETY_SCREENING"
            ),
            lastSummarizedSequence = 3
        )
        val summaryJson = objectMapper.writeValueAsString(summary)
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(summary = summaryJson, contextType = "GENERAL"))
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(summary, emptyList())
        every { fixture.contextBuilder.serializeSummary(summary) } returns summaryJson
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every { catalog.promptEvidence(any(), any(), any(), null, any(), any()) } returns AgentPromptEvidence()
        every {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, completed.captured.queryTarget)
        assertTrue(completed.captured.catalogItems.isEmpty())
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `recent catalog focus cannot be replaced by an incompatible human parser target`() {
        val content = "多少钱？"
        val summaryTopics = listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG")
        val summaryJson = objectMapper.writeValueAsString(AgentSessionSummary(unresolvedTopics = summaryTopics))
        val history = listOf(message(1, "USER", "推荐医生"))
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(summary = summaryJson))
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(unresolvedTopics = summaryTopics),
            history
        )
        every { fixture.contextBuilder.serializeSummary(any()) } returns summaryJson
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, listOf("推荐医生")) } returns content
        every { catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.DOCTOR, any(), any()) } returns
            AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString("CATALOG_QA/DOCTOR")))
            .andExpect(content().string(not(containsString("CATALOG_QA/INSTITUTION"))))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals("DOCTOR", completed.captured.queryTarget)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `institution project alternative institution excludes the offering institution representation`() {
        val content = "推荐其他机构"
        val offering = InstitutionProjectEntity(
            id = "offering-1",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000")
        )
        val institutionProjectRepository = mockk<InstitutionProjectRepository>()
        every { institutionProjectRepository.findById(offering.id) } returns java.util.Optional.of(offering)
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionProjectRepository = institutionProjectRepository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(contextType = "INSTITUTION_PROJECT", contextId = offering.id)
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        val oldInstitution = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = offering.institutionId,
            name = "当前套餐所属机构",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = offering.institutionId
        )
        val alternative = oldInstitution.copy(
            id = "institution-2",
            name = "替代机构",
            institutionId = "institution-2"
        )
        val sameInstitutionOffering = AgentCatalogItemResponse(
            type = "INSTITUTION_PROJECT",
            id = "offering-2",
            name = "当前机构的另一个套餐",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = offering.institutionId,
            projectId = "project-2"
        )
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "AUTO", content)
        } returns AgentPromptEvidence(
            context = "两家机构",
            report = AgentCatalogReportResponse(
                mode = "AUTO",
                title = "机构",
                summary = "",
                items = listOf(oldInstitution, sameInstitutionOffering, alternative),
                comparisonDimensions = emptyList(),
                warnings = emptyList()
            )
        )
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(listOf(alternative), completed.captured.catalogItems)
        assertTrue(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `institution project identity and scope lookup failures degrade without leaking the entry card`() {
        val content = "这家机构还有其他医生吗"
        val entryId = "offering-broken"
        val institutionProjectRepository = mockk<InstitutionProjectRepository>()
        every { institutionProjectRepository.findById(entryId) } throws IllegalStateException("lookup unavailable")
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionProjectRepository = institutionProjectRepository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(contextType = "INSTITUTION_PROJECT", contextId = entryId))
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        val entry = AgentCatalogItemResponse(
            type = "INSTITUTION_PROJECT",
            id = entryId,
            name = "不可重新出现的旧套餐",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap()
        )
        val doctor = AgentCatalogItemResponse(
            type = "DOCTOR",
            id = "doctor-2",
            name = "其他医生",
            subtitle = "主任医师",
            summary = "",
            attributes = emptyMap()
        )
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.DOCTOR, "AUTO", content)
        } returns singleItemEvidence("医生", doctor).let {
            it.copy(report = it.report!!.copy(items = listOf(entry, doctor)))
        }
        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertTrue(completed.captured.catalogItems.isEmpty())
        assertEquals("NONE", completed.captured.nextAction)
        assertTrue(completed.captured.content.contains("可靠筛选"))
        assertEquals("", completed.captured.modelName)
        assertTrue(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `institution project lookup failure enriches exclusion identity from exact evidence`() {
        data class Case(
            val content: String,
            val target: AgentQueryTarget,
            val oldItem: AgentCatalogItemResponse,
            val siblingOffering: AgentCatalogItemResponse,
            val alternative: AgentCatalogItemResponse
        )

        val entryId = "offering-broken"
        val oldInstitutionId = "institution-old"
        val oldProjectId = "project-old"
        val exactOffering = AgentCatalogItemResponse(
            type = "INSTITUTION_PROJECT",
            id = entryId,
            name = "当前套餐",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = oldInstitutionId,
            projectId = oldProjectId
        )
        val institutionCase = Case(
            content = "推荐其他机构",
            target = AgentQueryTarget.INSTITUTION,
            oldItem = AgentCatalogItemResponse(
                type = "INSTITUTION",
                id = oldInstitutionId,
                name = "旧所属机构",
                subtitle = "杭州",
                summary = "",
                attributes = emptyMap(),
                institutionId = oldInstitutionId
            ),
            siblingOffering = exactOffering.copy(id = "offering-sibling", name = "旧机构另一套餐", projectId = "project-2"),
            alternative = AgentCatalogItemResponse(
                type = "INSTITUTION",
                id = "institution-new",
                name = "安全替代机构",
                subtitle = "上海",
                summary = "",
                attributes = emptyMap(),
                institutionId = "institution-new"
            )
        )
        val projectCase = Case(
            content = "推荐其他项目",
            target = AgentQueryTarget.PROJECT,
            oldItem = AgentCatalogItemResponse(
                type = "PROJECT",
                id = oldProjectId,
                name = "旧所属项目",
                subtitle = "光电",
                summary = "",
                attributes = emptyMap(),
                projectId = oldProjectId
            ),
            siblingOffering = exactOffering.copy(id = "offering-sibling", name = "旧项目另一套餐", institutionId = "institution-2"),
            alternative = AgentCatalogItemResponse(
                type = "PROJECT",
                id = "project-new",
                name = "安全替代项目",
                subtitle = "光电",
                summary = "",
                attributes = emptyMap(),
                projectId = "project-new"
            )
        )

        listOf(institutionCase, projectCase).forEach { case ->
            val repository = mockk<InstitutionProjectRepository>()
            every { repository.findById(entryId) } throws IllegalStateException("lookup unavailable")
            val completionTemplate = RestTemplate()
            val intentTemplate = RestTemplate()
            val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
            val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
            val catalog = mockk<AgentCatalogService>()
            val fixture = chatFixture(
                completionTemplate,
                intentTemplate,
                catalog,
                institutionProjectRepository = repository
            )
            val completed = slot<CompleteTurnCommand>()
            prepareChatGeneration(
                fixture,
                case.content,
                session(contextType = "INSTITUTION_PROJECT", contextId = entryId)
            )
            every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
                ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
            )
            every { catalog.hasInstitutionProjectMatch(case.content) } returns false
            every { catalog.contextualSearchQuery(case.content, emptyList()) } returns case.content
            every {
                catalog.promptEvidence(
                    case.content,
                    case.content,
                    case.content,
                    case.target,
                    "AUTO",
                    case.content
                )
            } returns AgentPromptEvidence(
                context = "候选",
                report = AgentCatalogReportResponse(
                    mode = "AUTO",
                    title = "候选",
                    summary = "",
                    items = listOf(exactOffering, case.oldItem, case.siblingOffering, case.alternative),
                    comparisonDimensions = emptyList(),
                    warnings = emptyList()
                )
            )
            completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

            fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = case.content))

            assertEquals(listOf(case.alternative), completed.captured.catalogItems, case.target.toString())
            assertTrue(completed.captured.releaseDetailContext, case.target.toString())
            intentServer.verify()
            completionServer.verify()
        }
    }

    @Test
    fun `institution project exclusion fails closed when no relation can be verified`() {
        val content = "推荐其他机构"
        val entryId = "offering-broken"
        val repository = mockk<InstitutionProjectRepository>()
        every { repository.findById(entryId) } throws IllegalStateException("lookup unavailable")
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionProjectRepository = repository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(contextType = "INSTITUTION_PROJECT", contextId = entryId))
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "unavailable")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        val possibleOldInstitution = humanInstitutionItem().copy(name = "无法验证是否为旧机构")
        val alternative = humanInstitutionItem().copy(
            id = "institution-new",
            institutionId = "institution-new",
            name = "看似安全但无法证明的替代机构"
        )
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "AUTO", content)
        } returns AgentPromptEvidence(
            context = "无法验证的候选",
            report = AgentCatalogReportResponse(
                mode = "AUTO",
                title = "机构",
                summary = "",
                items = listOf(possibleOldInstitution, alternative),
                comparisonDimensions = emptyList(),
                warnings = emptyList()
            )
        )

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertTrue(completed.captured.catalogItems.isEmpty())
        assertTrue(completed.captured.content.contains("可靠筛选"))
        assertEquals("NONE", completed.captured.nextAction)
        assertEquals("", completed.captured.modelName)
        assertTrue(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `human consultation survives an institution project context lookup failure`() {
        val content = "转真人咨询"
        val entryId = "offering-broken"
        val institutionProjectRepository = mockk<InstitutionProjectRepository>()
        every { institutionProjectRepository.findById(entryId) } throws IllegalStateException("lookup unavailable")
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionProjectRepository = institutionProjectRepository
        )
        val completed = slot<CompleteTurnCommand>()
        val alternative = humanInstitutionItem()
        prepareChatGeneration(fixture, content, session(contextType = "INSTITUTION_PROJECT", contextId = entryId))
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
        )
        every {
            catalog.selectConsultableInstitutions("user-1", content, null)
        } returns ConsultableInstitutionSelection(listOf(alternative), false)

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
        assertEquals(listOf(alternative), completed.captured.catalogItems)
        verify(exactly = 1) { institutionProjectRepository.findById(entryId) }
        verify(exactly = 1) { catalog.selectConsultableInstitutions("user-1", content, null) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `negated human handoff fails closed when the offering institution cannot be resolved`() {
        val content = "不要这个项目，转真人咨询"
        val entryId = "offering-broken"
        val repository = mockk<InstitutionProjectRepository>()
        every { repository.findById(entryId) } throws IllegalStateException("lookup unavailable")
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionProjectRepository = repository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content, session(contextType = "INSTITUTION_PROJECT", contextId = entryId))
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "unavailable")
        )
        every {
            catalog.selectConsultableInstitutions("user-1", content, null)
        } returns ConsultableInstitutionSelection(
            listOf(
                humanInstitutionItem().copy(name = "可能是被拒绝的旧机构"),
                humanInstitutionItem().copy(
                    id = "institution-new",
                    institutionId = "institution-new",
                    name = "替代机构"
                )
            ),
            false
        )

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
        assertTrue(completed.captured.catalogItems.isEmpty())
        assertTrue(completed.captured.content.contains("可靠筛选"))
        assertEquals("NONE", completed.captured.nextAction)
        assertEquals("", completed.captured.modelName)
        assertTrue(completed.captured.releaseDetailContext)
        verify(exactly = 0) { catalog.selectConsultableInstitutions(any(), any(), any()) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `negated detail reference does not preselect that institution for human consultation`() {
        val content = "不想咨询这家机构，因为太贵，转真人咨询"
        val test = detailEntryChatFixture(content, "不应预选的旧机构", "真人转接")
        val alternative = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = "institution-2",
            name = "其他可咨询机构",
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = "institution-2",
            canChatWithHuman = true
        )
        val rejected = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = test.institution.id,
            name = test.institution.name,
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = test.institution.id,
            canChatWithHuman = true
        )
        every {
            test.catalog.selectConsultableInstitutions("user-1", content, null)
        } returns ConsultableInstitutionSelection(listOf(rejected, alternative), false)

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals("SELECT_INSTITUTION", test.completed.captured.nextAction)
        assertEquals(listOf(alternative), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        verify(exactly = 1) {
            test.catalog.selectConsultableInstitutions("user-1", content, null)
        }
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `detail summary after detail entry keeps its context prompt and card`() {
        val content = "请根据平台数据库信息，简要总结当前详情的关键信息、适合关注的方面和必要风险。"
        val test = detailEntryChatFixture(content, "当前机构应保留", "详情总结")
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString(test.institution.name)))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"详情总结"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("DETAIL_SUMMARY", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals("SHOW_CATALOG", test.completed.captured.nextAction)
        assertEquals(test.institution.id, test.completed.captured.catalogItems.single().id)
        assertFalse(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `same target price question keeps the entry context prompt and card`() {
        val content = "机构费用多少"
        val test = detailEntryChatFixture(content, "当前费用机构", "价格回答")
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString(test.institution.name)))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", test.completed.captured.intent)
        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `ambiguous consultation uses the intent model and skips the answer model`() {
        val content = "Could someone help me with this?"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val institutionItem = AgentCatalogItemResponse(
            type = "INSTITUTION", id = "institution-1", name = "安心医美", subtitle = "Shanghai", summary = "",
            attributes = emptyMap(), institutionId = "institution-1", canChatWithHuman = true
        )
        prepareChatGeneration(
            fixture,
            content,
            session(contextType = "INSTITUTION", contextId = institutionItem.id)
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.selectConsultableInstitutions("user-1", content, institutionItem.id) } returns
            ConsultableInstitutionSelection(listOf(institutionItem), false)
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString("humanOverrideAllowed=true")))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", completed.captured.intent)
        assertEquals("", completed.captured.modelName)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `explicit human topic boundary does not preselect or retain the entry institution`() {
        val content = "换个话题，我不确定是否需要真人咨询"
        val test = detailEntryChatFixture(content, "换题后不应预选的旧机构", "真人转接")
        val rejected = AgentCatalogItemResponse(
            type = "INSTITUTION",
            id = test.institution.id,
            name = test.institution.name,
            subtitle = "杭州",
            summary = "",
            attributes = emptyMap(),
            institutionId = test.institution.id,
            canChatWithHuman = true
        )
        val alternative = rejected.copy(
            id = "institution-new",
            name = "新话题可咨询机构",
            institutionId = "institution-new"
        )
        every {
            test.catalog.selectConsultableInstitutions("user-1", content, null)
        } returns ConsultableInstitutionSelection(listOf(rejected, alternative), false)

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", test.completed.captured.intent)
        assertEquals(listOf(alternative), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        verify(exactly = 1) {
            test.catalog.selectConsultableInstitutions("user-1", content, null)
        }
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `explicit safety topic boundary releases the entry detail after the safety answer`() {
        val content = "换个话题，我怀孕了能做吗"
        val test = detailEntryChatFixture(content, "安全换题后不应保留的旧机构", "安全回答")
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"安全回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("SAFETY_SCREENING", test.completed.captured.intent)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `explicit topic boundary parses an uncertain current safety request`() {
        val content = "换个话题，我不确定是否怀孕，可以做吗"
        val test = detailEntryChatFixture(content, "不确定换题不应复活的旧机构", "answer")
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"SAFETY_SCREENING\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(not(containsString(test.institution.name))))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"安全回答"}}]}""",
                MediaType.APPLICATION_JSON
            ))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("SAFETY_SCREENING", test.completed.captured.intent)
        assertTrue(test.completed.captured.releaseDetailContext)
        assertEquals(ConversationFocusUpdate.CLEAR, test.completed.captured.conversationFocusUpdate)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `final safety metadata keeps detail focus when the raw request was uncertain`() {
        val content = "多少钱？"
        val priorTurnId = "uncertain-safety-turn"
        val priorUser = message(1, "USER", "我不确定是否怀孕，可以做吗").apply { turnId = priorTurnId }
        val priorAssistant = message(2, "ASSISTANT", "安全回答").apply {
            turnId = priorTurnId
            metadataJson = objectMapper.writeValueAsString(
                linkedMapOf(
                    "intent" to "SAFETY_SCREENING",
                    "queryTarget" to null,
                    "nextAction" to "NONE",
                    "conversationFocusUpdate" to "PRESERVE",
                    "catalogItems" to emptyList<AgentCatalogItemResponse>(),
                    "catalogReport" to null
                )
            )
        }
        val test = detailEntryChatFixture(
            content,
            "安全覆盖层后仍应恢复的机构",
            "价格回答",
            historyMessages = listOf(priorUser, priorAssistant),
            summary = AgentSessionSummary(
                focusSemanticsVersion = 1,
                unresolvedTopics = listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
                lastSummarizedSequence = 1
            )
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `legacy final safety metadata keeps detail focus when the raw request was uncertain`() {
        val content = "多少钱？"
        val priorTurnId = "legacy-uncertain-safety-turn"
        val priorUser = message(1, "USER", "我不确定是否怀孕，可以做吗").apply { turnId = priorTurnId }
        val priorAssistant = message(2, "ASSISTANT", "安全回答").apply {
            turnId = priorTurnId
            metadataJson = objectMapper.writeValueAsString(
                linkedMapOf(
                    "intent" to "SAFETY_SCREENING",
                    "queryTarget" to null,
                    "nextAction" to "NONE",
                    "catalogItems" to emptyList<AgentCatalogItemResponse>(),
                    "catalogReport" to null
                )
            )
        }
        val test = detailEntryChatFixture(
            content,
            "旧版安全覆盖层后仍应恢复的机构",
            "价格回答",
            historyMessages = listOf(priorUser, priorAssistant),
            summary = AgentSessionSummary(
                unresolvedTopics = listOf(
                    "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                    "SAFETY_SCREENING"
                ),
                lastSummarizedSequence = 1
            )
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `legacy final human metadata keeps detail focus when the raw request needed model classification`() {
        val content = "多少钱？"
        val priorTurnId = "legacy-human-turn"
        val priorUser = message(1, "USER", "Can somebody guide me personally?").apply { turnId = priorTurnId }
        val priorAssistant = message(2, "ASSISTANT", "真人咨询引导").apply {
            turnId = priorTurnId
            metadataJson = objectMapper.writeValueAsString(
                linkedMapOf(
                    "intent" to "HUMAN_CONSULTATION",
                    "queryTarget" to "INSTITUTION",
                    "nextAction" to "SELECT_INSTITUTION",
                    "catalogItems" to emptyList<AgentCatalogItemResponse>(),
                    "catalogReport" to null
                )
            )
        }
        val test = detailEntryChatFixture(
            content,
            "旧版真人覆盖层后仍应恢复的机构",
            "价格回答",
            historyMessages = listOf(priorUser, priorAssistant),
            summary = AgentSessionSummary(
                unresolvedTopics = listOf(
                    "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                    "HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION"
                ),
                lastSummarizedSequence = 1
            )
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"价格回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("INSTITUTION", test.completed.captured.queryTarget)
        assertEquals(listOf(test.institution.id), test.completed.captured.catalogItems.map { it.id })
        assertFalse(test.completed.captured.releaseDetailContext)
        test.completionServer.verify()
    }

    @Test
    fun `legacy overlay window cannot revive an explicit safety topic boundary`() {
        val content = "多少钱？"
        data class LegacyOverlay(val user: String, val intent: String, val target: String?, val action: String)
        val history = listOf(
            LegacyOverlay("换个话题，我不确定是否怀孕，可以做吗", "SAFETY_SCREENING", null, "COMPLETE_SAFETY_SCREENING"),
            LegacyOverlay("我不确定是否需要真人咨询", "HUMAN_CONSULTATION", "INSTITUTION", "SELECT_INSTITUTION"),
            LegacyOverlay("我怀孕了，可以做吗", "SAFETY_SCREENING", null, "COMPLETE_SAFETY_SCREENING"),
            LegacyOverlay("请帮我转真人咨询", "HUMAN_CONSULTATION", "INSTITUTION", "SELECT_INSTITUTION"),
            LegacyOverlay("I am pregnant; is this safe?", "SAFETY_SCREENING", null, "COMPLETE_SAFETY_SCREENING")
        ).flatMapIndexed { index, overlay ->
            val turnId = "legacy-overlay-turn-${index + 1}"
            listOf(
                message(index * 2L + 1, "USER", overlay.user).apply { this.turnId = turnId },
                message(index * 2L + 2, "ASSISTANT", "overlay answer").apply {
                    this.turnId = turnId
                    metadataJson = objectMapper.writeValueAsString(
                        linkedMapOf(
                            "intent" to overlay.intent,
                            "queryTarget" to overlay.target,
                            "nextAction" to overlay.action,
                            "catalogItems" to emptyList<AgentCatalogItemResponse>(),
                            "catalogReport" to null
                        )
                    )
                }
            )
        }
        val summary = AgentSessionSummary(
            unresolvedTopics = listOf(
                "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                "SAFETY_SCREENING:COMPLETE_SAFETY_SCREENING",
                "HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION"
            ),
            lastSummarizedSequence = 5
        )
        val test = detailEntryChatFixture(
            content,
            "旧版安全换题后不能复活的机构",
            "普通回答",
            historyMessages = history,
            summary = summary
        )
        every {
            test.catalog.promptEvidence(any(), any(), any(), null, any(), any())
        } returns AgentPromptEvidence()
        every {
            test.catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, any(), any())
        } returns AgentPromptEvidence()
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        test.completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"普通回答"}}]}""", MediaType.APPLICATION_JSON))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, test.completed.captured.queryTarget)
        assertTrue(test.completed.captured.catalogItems.isEmpty())
        assertTrue(test.completed.captured.releaseDetailContext)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `explicit boundary deictic human request uses the intent model without the old institution`() {
        val content = "换个话题，Could someone help me with this?"
        val test = detailEntryChatFixture(content, "模型真人换题不应复活的旧机构", "handoff")
        val alternative = humanInstitutionItem()
        every {
            test.catalog.selectConsultableInstitutions("user-1", content, null)
        } returns ConsultableInstitutionSelection(listOf(alternative), false)
        test.intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))

        test.fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("HUMAN_CONSULTATION", test.completed.captured.intent)
        assertEquals(listOf(alternative), test.completed.captured.catalogItems)
        assertTrue(test.completed.captured.releaseDetailContext)
        assertEquals(ConversationFocusUpdate.CLEAR, test.completed.captured.conversationFocusUpdate)
        test.intentServer.verify()
        test.completionServer.verify()
    }

    @Test
    fun `bare deictic human parser upgrade works from non institution detail contexts`() {
        data class Case(
            val contextType: String,
            val contextId: String,
            val expectedInstitutionId: String?
        )

        val content = "Could someone help me with this?"
        listOf(
            Case("DOCTOR", "doctor-1", null),
            Case("PROJECT", "project-1", null),
            Case("INSTITUTION_PROJECT", "offering-1", "institution-1")
        ).forEach { case ->
            val completionTemplate = RestTemplate()
            val intentTemplate = RestTemplate()
            val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
            val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
            val catalog = mockk<AgentCatalogService>()
            val institutionProjectRepository = mockk<InstitutionProjectRepository>()
            if (case.contextType == "INSTITUTION_PROJECT") {
                every { institutionProjectRepository.findById(case.contextId) } returns java.util.Optional.of(
                    InstitutionProjectEntity(
                        id = case.contextId,
                        institutionId = case.expectedInstitutionId!!,
                        projectId = "project-1",
                        price = BigDecimal("1000")
                    )
                )
            }
            val fixture = chatFixture(
                completionTemplate,
                intentTemplate,
                catalog,
                institutionProjectRepository = institutionProjectRepository
            )
            val completed = slot<CompleteTurnCommand>()
            val institutionItem = humanInstitutionItem().copy(
                id = case.expectedInstitutionId ?: "institution-2",
                institutionId = case.expectedInstitutionId ?: "institution-2"
            )
            prepareChatGeneration(
                fixture,
                content,
                session(contextType = case.contextType, contextId = case.contextId)
            )
            every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
                ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
            )
            every { catalog.hasInstitutionProjectMatch(content) } returns false
            every {
                catalog.selectConsultableInstitutions("user-1", content, case.expectedInstitutionId)
            } returns ConsultableInstitutionSelection(listOf(institutionItem), false)
            intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(content().string(containsString("humanOverrideAllowed=true")))
                .andRespond(withSuccess(
                    """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                    MediaType.APPLICATION_JSON
                ))

            fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

            assertEquals("HUMAN_CONSULTATION", completed.captured.intent, case.contextType)
            assertEquals("INSTITUTION", completed.captured.queryTarget, case.contextType)
            assertEquals(listOf(institutionItem), completed.captured.catalogItems, case.contextType)
            assertEquals("", completed.captured.modelName, case.contextType)
            verify(exactly = 1) {
                catalog.selectConsultableInstitutions("user-1", content, case.expectedInstitutionId)
            }
            intentServer.verify()
            completionServer.verify()
        }
    }

    @Test
    fun `human parser route rejects a doctor target`() {
        val content = "Could someone help me with this?"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every { catalog.promptEvidence(content, content, content, null, "AUTO", content) } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"HUMAN_CONSULTATION\",\"queryTarget\":\"DOCTOR\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("GENERAL_CHAT", completed.captured.intent)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `empty human consultation candidates persist NONE summary and replay snapshot`() {
        val content = "我想转人工咨询"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog, lifecycle, context)
        val ownedSession = session()
        val persistedTurns = linkedMapOf<String, AgentTurnEntity>()
        val persistedMessages = linkedMapOf<Pair<String, String>, ChatMessageEntity>()
        var turnSaveCount = 0
        var messageSaveCount = 0
        var sessionSaveCount = 0
        every { fixture.availabilityGuard.requireGenerationEnabled() } just runs
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns ownedSession
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-1") } returns ownedSession
        every { sessions.findByIdForUpdate("session-1") } returns ownedSession
        every { sessions.save(any()) } answers {
            sessionSaveCount += 1
            firstArg()
        }
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } answers {
            persistedTurns.values.singleOrNull()
        }
        every { turns.findBySessionIdAndStatus("session-1", AgentTurnStatus.RUNNING) } returns null
        every { turns.findSessionIdById(any()) } answers { persistedTurns[firstArg()]?.sessionId }
        every { turns.findByIdForUpdate(any()) } answers { persistedTurns[firstArg()] }
        every { turns.save(any()) } answers {
            turnSaveCount += 1
            firstArg<AgentTurnEntity>().also { persistedTurns[it.id] = it }
        }
        every { messages.findByTurnIdAndRole(any(), any()) } answers {
            persistedMessages[firstArg<String>() to secondArg<String>()]
        }
        every { messages.save(any()) } answers {
            messageSaveCount += 1
            firstArg<ChatMessageEntity>().also { message ->
                persistedMessages[requireNotNull(message.turnId) to message.role] = message
            }
        }
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } answers {
            persistedMessages.values.sortedBy { it.sequenceNo }
        }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs
        every { fixture.operationLogger.completed(any(), any(), any(), any(), any()) } just runs
        every { catalog.selectConsultableInstitutions("user-1", content, null) } returns
            ConsultableInstitutionSelection(emptyList(), false)

        val completed = fixture.chat.sendMessage(
            "session-1",
            "user-1",
            SendMessageRequest(content = content, idempotencyKey = "key-1")
        )

        val persistedAssistant = persistedMessages.values.single { it.role == "ASSISTANT" }
        val metadata = objectMapper.readTree(persistedAssistant.metadataJson)
        val summary = objectMapper.readTree(ownedSession.summaryJson)
        assertEquals("NONE", completed.nextAction)
        assertTrue(completed.catalogItems.isEmpty())
        assertEquals("HUMAN_CONSULTATION", metadata.path("intent").asText())
        assertEquals("INSTITUTION", metadata.path("queryTarget").asText())
        assertEquals("NONE", metadata.path("nextAction").asText())
        assertTrue(metadata.path("catalogItems").isEmpty)
        assertEquals(
            listOf("HUMAN_CONSULTATION:INSTITUTION"),
            summary.path("unresolvedTopics").map { it.asText() }
        )
        assertFalse(ownedSession.summaryJson.contains(":NONE"))

        val savesBeforeReplay = Triple(turnSaveCount, messageSaveCount, sessionSaveCount)
        val replay = fixture.chat.sendMessage(
            "session-1",
            "user-1",
            SendMessageRequest(content = content, idempotencyKey = "key-1")
        )

        assertEquals("HUMAN_CONSULTATION", replay.intent)
        assertEquals("INSTITUTION", replay.queryTarget)
        assertEquals("NONE", replay.nextAction)
        assertTrue(replay.catalogItems.isEmpty())
        assertEquals(savesBeforeReplay, Triple(turnSaveCount, messageSaveCount, sessionSaveCount))
        verify(exactly = 1) { catalog.selectConsultableInstitutions("user-1", content, null) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `unavailable named institution returns alternatives with SELECT_INSTITUTION`() {
        val content = "请帮我转接安心医美的真人咨询"
        val institutionItem = AgentCatalogItemResponse(
            type = "INSTITUTION", id = "institution-2", name = "星辰医美", subtitle = "Shanghai", summary = "",
            attributes = emptyMap(), institutionId = "institution-2", canChatWithHuman = true
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "handoff")
        )
        every { catalog.selectConsultableInstitutions("user-1", content, null) } returns
            ConsultableInstitutionSelection(listOf(institutionItem), true)

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("SELECT_INSTITUTION", completed.captured.nextAction)
        assertEquals(institutionItem, completed.captured.catalogItems.single())
        assertEquals("你提到的机构目前无法提供真人转接。你可以选择下方其他机构，查看其当前可联系的咨询师。", completed.captured.content)
        assertEquals("", completed.captured.modelName)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `parser cannot replace explicit comparison intent or institution target`() {
        val local = AgentRouteAssessment(
            decision = AgentIntentRouter().validatedDecision(AgentIntent.COMPARISON, AgentQueryTarget.INSTITUTION),
            confidence = 0.60,
            ambiguityReasons = listOf("CONFLICTING_CONTEXT"),
            requiresLlmParsing = true,
            explicitIntent = true,
            explicitQueryTarget = true
        )

        val result = AgentIntentRouter().mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.PLANNING, AgentQueryTarget.DOCTOR, emptyList())
        )

        assertEquals(AgentIntent.COMPARISON, result.intent)
        assertEquals(AgentQueryTarget.INSTITUTION, result.queryTarget)
    }

    @Test
    fun `parser fills an unresolved comparison target`() {
        val local = AgentIntentRouter().assessCurrent("对比一下", "GENERAL")

        val result = AgentIntentRouter().mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.COMPARISON, AgentQueryTarget.PROJECT, emptyList())
        )

        assertEquals(AgentIntent.COMPARISON, result.intent)
        assertEquals(AgentQueryTarget.PROJECT, result.queryTarget)
    }

    @Test
    fun `parser cannot downgrade deterministic safety to general chat`() {
        val local = AgentIntentRouter().assessCurrent("我怀孕了，想比较项目", "GENERAL")

        val result = AgentIntentRouter().mergeParsedRoute(
            local,
            ParsedAgentRoute(AgentIntent.GENERAL_CHAT, null, emptyList())
        )

        assertEquals(AgentIntent.SAFETY_SCREENING, result.intent)
    }

    @Test
    fun `parser safety label upgrades before locked business target compatibility`() {
        assertParserSafetyUpgrade(
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"intents\":[\"COMPARISON\",\"SAFETY_SCREENING\"],\"queryTarget\":null,\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            )
        )
    }

    @Test
    fun `legacy parser safety intent upgrades with the locked business target`() {
        assertParserSafetyUpgrade(
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"SAFETY_SCREENING\",\"queryTarget\":\"PROJECT\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            )
        )
    }

    @Test
    fun `human parser target rejection precedes safety negation upgrade`() {
        val content = "I am not not pregnant; compare treatments"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "clarification")
        )
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.PROJECT, "COMPARISON", content)
        } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"intents\":[\"COMPARISON\",\"SAFETY_SCREENING\",\"HUMAN_CONSULTATION\"],\"queryTarget\":\"DOCTOR\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("COMPARISON", completed.captured.intent)
        assertEquals("PROJECT", completed.captured.queryTarget)
        assertEquals("", completed.captured.modelName)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `invalid optional intents member keeps local route and still completes`() {
        assertParserFailureStillCompletes(
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"intents\":[\"CATALOG_QA\",\"NOT_ALLOWED\"],\"queryTarget\":\"PROJECT\",\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            )
        )
    }

    @Test
    fun `strict parser rejects wrapper text unknown fields and multiple objects`() {
        listOf(
            """Here is the result: {"intent":"CATALOG_QA","queryTarget":"PROJECT","keywords":["fixture-keyword"]}""",
            """{"intent":"CATALOG_QA","queryTarget":"PROJECT","keywords":["fixture-keyword"],"unexpected":true}""",
            """{"intent":"CATALOG_QA","queryTarget":"PROJECT","keywords":["fixture-keyword"]} {"intent":"GENERAL_CHAT","queryTarget":null,"keywords":[]}"""
        ).forEach { parserContent ->
            val responseBody = objectMapper.writeValueAsString(
                mapOf("choices" to listOf(mapOf("message" to mapOf("content" to parserContent))))
            )
            assertParserFailureStillCompletes(withSuccess(responseBody, MediaType.APPLICATION_JSON))
        }
    }

    @Test
    fun `intent parser failures keep the local route and still complete`() {
        listOf<ResponseCreator>(
            withException(SocketTimeoutException("intent parser timed out")),
            withStatus(HttpStatus.BAD_GATEWAY),
            withSuccess("""{"choices":[{"message":{"content":"not-json"}}]}""", MediaType.APPLICATION_JSON),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"NOT_ALLOWED\",\"queryTarget\":\"PROJECT\"}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"PROJECT\"}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"PROJECT\",\"keywords\":\"fixture-keyword\"}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"PROJECT\",\"keywords\":[{\"value\":\"fixture-keyword\"}]}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"GENERAL_CHAT\",\"queryTarget\":\"PROJECT\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"queryTarget\":\"null\",\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            ),
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"queryTarget\":null,\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            )
        ).forEach(::assertParserFailureStillCompletes)
    }

    @Test
    fun `generation prompt preserves safety comparison and institution labels`() {
        assertGenerationPromptLabels(
            userContent = "怀孕期间比较两个机构",
            expectedPrimaryIntent = "SAFETY_SCREENING",
            expectedLabels = listOf(
                "请求动作：SAFETY_SCREENING,COMPARISON",
                "请求对象：INSTITUTION"
            )
        )
    }

    @Test
    fun `generation prompt preserves comparison planning and project labels`() {
        assertGenerationPromptLabels(
            userContent = "比较 Alpha 项目和 Beta 项目并制定方案",
            expectedPrimaryIntent = "COMPARISON",
            expectedLabels = listOf(
                "请求动作：COMPARISON,PLANNING",
                "请求对象：PROJECT"
            )
        )
    }

    @Test
    fun `complete comparison calls catalog once and passes only exact operands to answer model`() {
        val content = "Compare Alpha Clinic and Beta Clinic"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val filteredRequest = slot<ComparisonRequest>()
        val alpha = comparisonItem("alpha", "Alpha Clinic")
        val beta = comparisonItem("beta", "Beta Clinic")
        val ranked = comparisonItem("ranked", "Ranked Clinic")
        val rawEvidence = comparisonEvidence(alpha, ranked, beta)
        val filteredEvidence = comparisonEvidence(alpha, beta).copy(
            context = "Platform database comparison evidence: Alpha Clinic; Beta Clinic"
        )
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "COMPARISON", content)
        } returns rawEvidence
        every { catalog.filterComparisonEvidence(rawEvidence, capture(filteredRequest)) } returns filteredEvidence
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString("Alpha Clinic")))
            .andExpect(content().string(containsString("Beta Clinic")))
            .andExpect(content().string(not(containsString("Ranked Clinic"))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("COMPARISON", completed.captured.intent)
        assertEquals(listOf("alpha", "beta"), filteredRequest.captured.operands.map { it.entityId })
        assertEquals(listOf("alpha", "beta"), completed.captured.comparisonRequest?.operands?.map { it.entityId })
        verify(exactly = 1) {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "COMPARISON", content)
        }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `single fuzzy comparison result is preserved and asks for another operand`() {
        val content = "Compare clinics"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val rawEvidence = comparisonEvidence(comparisonItem("ranked", "Ranked Clinic"))
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "clarification")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "COMPARISON", content)
        } returns rawEvidence

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertTrue(completed.captured.content.contains("keyword search found", ignoreCase = true))
        assertTrue(completed.captured.content.contains("Ranked Clinic"))
        assertTrue(completed.captured.content.contains("one more", ignoreCase = true))
        assertEquals(setOf(ComparisonMissingField.OPERANDS), completed.captured.comparisonRequest?.missingFields)
        assertEquals("", completed.captured.modelName)
        assertEquals(rawEvidence.report, completed.captured.catalogReport)
        assertEquals(listOf("ranked"), completed.captured.catalogItems.map { it.id })
        verify(exactly = 0) { catalog.filterComparisonEvidence(any(), any()) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `fuzzy comparison with a small result set compares discovered candidates`() {
        val content = "Compare treatments"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val filteredRequest = slot<ComparisonRequest>()
        val first = comparisonItem("thermage", "Thermage", type = "PROJECT")
        val second = comparisonItem("ultherapy", "Ultherapy", type = "PROJECT")
        val rawEvidence = comparisonEvidence(first, second)
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.PROJECT, "COMPARISON", content)
        } returns rawEvidence
        every { catalog.filterComparisonEvidence(rawEvidence, capture(filteredRequest)) } returns rawEvidence
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(listOf("thermage", "ultherapy"), filteredRequest.captured.operands.map { it.entityId })
        assertTrue(completed.captured.comparisonRequest?.isComplete == true)
        verify(exactly = 1) { catalog.filterComparisonEvidence(rawEvidence, any()) }
        completionServer.verify()
    }

    @Test
    fun `fuzzy comparison with too many results keeps examples and asks to narrow scope`() {
        val content = "对比一下皮肤相关的项目"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val candidates = (1..4).map { comparisonItem("skin-$it", "皮肤项目$it", type = "PROJECT") }
        val rawEvidence = comparisonEvidence(*candidates.toTypedArray(), totalMatched = 9)
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "clarification")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.PROJECT, "COMPARISON", content)
        } returns rawEvidence

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertTrue(completed.captured.content.contains("9"))
        assertTrue(completed.captured.content.contains("缩小范围"))
        assertTrue(completed.captured.content.contains("机构"))
        assertTrue(completed.captured.content.contains("城市"))
        assertTrue(completed.captured.content.contains("价格"))
        assertEquals(4, completed.captured.catalogItems.size)
        assertEquals(setOf(ComparisonMissingField.OPERANDS), completed.captured.comparisonRequest?.missingFields)
        verify(exactly = 0) { catalog.filterComparisonEvidence(any(), any()) }
        completionServer.verify()
    }

    @Test
    fun `target only follow up completes broader institution project comparison`() {
        val content = "机构项目"
        val priorUser = message(1, "USER", "对比热玛吉和玻尿酸")
        val assistant = message(2, "ASSISTANT", "请明确比较类型")
        val previous = ComparisonRequestBuilder().normalize(ComparisonRequest())
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val thermage = comparisonItem("thermage", "悦美机构 · 热玛吉紧肤", type = "INSTITUTION_PROJECT")
        val filler = comparisonItem("filler", "安心诊所 · 玻尿酸填充", type = "INSTITUTION_PROJECT")
        val rawEvidence = comparisonEvidence(thermage, filler)
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(priorUser, assistant))
        every { fixture.turnService.projectMessage(assistant) } returns AgentMessageProjection(
            message = assistant,
            intent = "COMPARISON",
            queryTarget = null,
            nextAction = "SHOW_CATALOG",
            comparisonRequest = previous
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.contextualSearchQuery(content, listOf(priorUser.content)) } returns content
        every {
            catalog.promptEvidence(
                content,
                "$content ${priorUser.content}",
                "$content ${priorUser.content}",
                AgentQueryTarget.INSTITUTION_PROJECT,
                "COMPARISON",
                content
            )
        } returns rawEvidence
        every { catalog.filterComparisonEvidence(rawEvidence, any()) } returns rawEvidence
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("COMPARISON", completed.captured.intent)
        assertEquals(AgentQueryTarget.INSTITUTION_PROJECT.name, completed.captured.queryTarget)
        assertEquals(listOf("thermage", "filler"), completed.captured.comparisonRequest?.operands?.map { it.entityId })
        assertTrue(completed.captured.comparisonRequest?.isComplete == true)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `later comparison restores the latest incomplete request from loaded assistant metadata`() {
        val content = "Compare Beta Clinic with clinics"
        val assistant = message(2, "ASSISTANT", "choose another").apply { turnId = "previous-turn" }
        val previous = ComparisonRequestBuilder().normalize(
            ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.INSTITUTION, " alpha ", " Alpha Clinic ")),
                targetType = AgentQueryTarget.INSTITUTION
            )
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val searchQuery = slot<String>()
        val targetQuery = slot<String>()
        val alpha = comparisonItem("alpha", "Alpha Clinic")
        val beta = comparisonItem("beta", "Beta Clinic")
        val realFilter = comparisonFilter()
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(assistant))
        every { fixture.turnService.projectMessage(assistant) } returns AgentMessageProjection(
            message = assistant,
            intent = "COMPARISON",
            queryTarget = "INSTITUTION",
            nextAction = "SHOW_CATALOG",
            comparisonRequest = previous
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(
                content,
                capture(searchQuery),
                capture(targetQuery),
                AgentQueryTarget.INSTITUTION,
                "COMPARISON",
                content
            )
        } answers {
            comparisonEvidence(
                *listOfNotNull(alpha.takeIf { secondArg<String>().contains(it.name) }, beta).toTypedArray()
            )
        }
        every { catalog.filterComparisonEvidence(any(), any()) } answers {
            realFilter.filterComparisonEvidence(firstArg(), secondArg())
        }
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("$content Alpha Clinic", searchQuery.captured)
        assertEquals(searchQuery.captured, targetQuery.captured)
        assertEquals(listOf("alpha", "beta"), completed.captured.comparisonRequest?.operands?.map { it.entityId })
        assertEquals(listOf("alpha", "beta"), completed.captured.catalogReport?.items?.map { it.id })
        verify(exactly = 1) {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, "COMPARISON", any())
        }
        verify(exactly = 1) { fixture.turnService.projectMessage(assistant) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `operand free comparison restores complete request after operand names leave query window`() {
        val content = "Compare those clinics again"
        val priorUser = message(1, "USER", "Compare Alpha Clinic and Beta Clinic")
        val assistant = message(2, "ASSISTANT", "prior comparison")
        val interveningUsers = (3L..7L).map { sequenceNo ->
            message(sequenceNo, "USER", "Unrelated follow-up $sequenceNo")
        }
        val previous = ComparisonRequest(
            operands = listOf(
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "alpha", "Alpha Clinic"),
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "beta", "Beta Clinic")
            ),
            targetType = AgentQueryTarget.INSTITUTION
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val searchQuery = slot<String>()
        val targetQuery = slot<String>()
        val filteredEvidence = slot<AgentPromptEvidence>()
        val filteredRequest = slot<ComparisonRequest>()
        val alpha = comparisonItem("alpha", "Alpha Clinic")
        val beta = comparisonItem("beta", "Beta Clinic")
        val rawEvidence = comparisonEvidence(alpha, beta)
        val realFilter = comparisonFilter()
        val contextualUserQueries = interveningUsers.takeLast(4).map { it.content }
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(priorUser, assistant) + interveningUsers)
        every { fixture.turnService.projectMessage(assistant) } returns AgentMessageProjection(
            message = assistant,
            intent = "COMPARISON",
            queryTarget = "INSTITUTION",
            nextAction = "SHOW_CATALOG",
            comparisonRequest = previous
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, contextualUserQueries) } returns content
        every {
            catalog.promptEvidence(
                content,
                capture(searchQuery),
                capture(targetQuery),
                AgentQueryTarget.INSTITUTION,
                "COMPARISON",
                content
            )
        } answers {
            if (secondArg<String>().contains(alpha.name) && secondArg<String>().contains(beta.name)) {
                rawEvidence
            } else {
                comparisonEvidence()
            }
        }
        every {
            catalog.filterComparisonEvidence(capture(filteredEvidence), capture(filteredRequest))
        } answers {
            realFilter.filterComparisonEvidence(firstArg(), secondArg())
        }
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString("Alpha Clinic")))
            .andExpect(content().string(containsString("Beta Clinic")))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("$content Alpha Clinic Beta Clinic", searchQuery.captured)
        assertEquals(searchQuery.captured, targetQuery.captured)
        assertEquals(rawEvidence, filteredEvidence.captured)
        assertEquals(listOf("alpha", "beta"), filteredRequest.captured.operands.map { it.entityId })
        assertEquals(listOf("alpha", "beta"), completed.captured.comparisonRequest?.operands?.map { it.entityId })
        assertEquals(listOf("alpha", "beta"), completed.captured.catalogReport?.items?.map { it.id })
        verify(exactly = 1) {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, "COMPARISON", any())
        }
        verify(exactly = 1) { catalog.filterComparisonEvidence(any(), any()) }
        verify(exactly = 1) { fixture.turnService.projectMessage(assistant) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `one current operand does not extend a complete previous request`() {
        val content = "Compare Gamma Clinic with clinics"
        val assistant = message(2, "ASSISTANT", "prior comparison")
        val previous = ComparisonRequest(
            operands = listOf(
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "alpha", "Alpha Clinic"),
                ComparisonOperand(AgentQueryTarget.INSTITUTION, "beta", "Beta Clinic")
            ),
            targetType = AgentQueryTarget.INSTITUTION
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val rawEvidence = comparisonEvidence(
            comparisonItem("alpha", "Alpha Clinic"),
            comparisonItem("beta", "Beta Clinic"),
            comparisonItem("gamma", "Gamma Clinic")
        )
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(assistant))
        every { fixture.turnService.projectMessage(assistant) } returns AgentMessageProjection(
            message = assistant,
            intent = "COMPARISON",
            comparisonRequest = previous
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "clarification")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(
                content,
                "$content Alpha Clinic Beta Clinic",
                "$content Alpha Clinic Beta Clinic",
                AgentQueryTarget.INSTITUTION,
                "COMPARISON",
                content
            )
        } returns rawEvidence

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(listOf("gamma"), completed.captured.comparisonRequest?.operands?.map { it.entityId })
        assertEquals(setOf(ComparisonMissingField.OPERANDS), completed.captured.comparisonRequest?.missingFields)
        completionServer.verify()
        intentServer.verify()
    }

    @Test
    fun `non comparison does not read or persist comparison state`() {
        val content = "我怀孕了"
        val assistant = message(2, "ASSISTANT", "prior comparison")
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(assistant))
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals(null, completed.captured.comparisonRequest)
        verify(exactly = 0) { fixture.turnService.projectMessage(any()) }
        verify(exactly = 0) { catalog.promptEvidence(any(), any(), any(), any(), any(), any()) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `comparison does not add an intent or catalog provider call`() {
        val content = "Compare Alpha and Beta"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val alpha = comparisonItem("alpha", "Alpha")
        val beta = comparisonItem("beta", "Beta")
        val rawEvidence = comparisonEvidence(alpha, beta)
        prepareChatGeneration(fixture, content)
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "COMPARISON", content)
        } returns rawEvidence
        every { catalog.filterComparisonEvidence(rawEvidence, any()) } returns rawEvidence
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().json("""{"model":"intent-small"}""", false))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().json("""{"model":"answer-model"}""", false))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        verify(exactly = 1) {
            catalog.promptEvidence(content, content, content, AgentQueryTarget.INSTITUTION, "COMPARISON", content)
        }
        verify(exactly = 1) { catalog.filterComparisonEvidence(rawEvidence, any()) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `generation prompt keeps negative action only as prohibition`() {
        assertGenerationPromptLabels(
            userContent = "不要比较机构，请制定项目方案",
            expectedPrimaryIntent = "PLANNING",
            expectedLabels = listOf(
                "请求动作：PLANNING",
                "禁止动作：COMPARISON"
            ),
            absentLabels = listOf("请求动作：PLANNING,COMPARISON")
        )
    }

    @Test
    fun `parser failure keeps local positive labels in generation prompt`() {
        assertGenerationPromptLabels(
            userContent = "我不确定是否怀孕，比较 Alpha 项目和 Beta 项目并制定方案",
            expectedPrimaryIntent = "COMPARISON",
            expectedLabels = listOf(
                "请求动作：COMPARISON,PLANNING",
                "请求对象：PROJECT",
                "待澄清动作：SAFETY_SCREENING"
            ),
            parserResponse = withException(SocketTimeoutException("intent parser timed out"))
        )
    }

    @Test
    fun `first turn in project context completes locally without intent parser`() {
        val content = "多久能恢复？"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(contextType = "PROJECT", contextId = "project-1")
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every { catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.PROJECT, any(), any()) } returns AgentPromptEvidence()
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals("PROJECT", completed.captured.queryTarget)
        assertFalse(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `explicit topic boundary does not restore catalog queries or comparison operands`() {
        val content = "换个话题，比较医生"
        val priorUser = message(1, "USER", "比较张医生和李医生")
        val assistant = message(2, "ASSISTANT", "prior comparison")
        val previous = ComparisonRequest(
            operands = listOf(
                ComparisonOperand(AgentQueryTarget.DOCTOR, "doctor-a", "张医生"),
                ComparisonOperand(AgentQueryTarget.DOCTOR, "doctor-b", "李医生")
            ),
            targetType = AgentQueryTarget.DOCTOR
        )
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        val contextualHistory = slot<List<String>>()
        val searchQuery = slot<String>()
        val oldEvidence = comparisonEvidence(
            comparisonItem("doctor-a", "张医生", "DOCTOR"),
            comparisonItem("doctor-b", "李医生", "DOCTOR")
        )
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), listOf(priorUser, assistant))
        every { fixture.turnService.projectMessage(assistant) } returns AgentMessageProjection(
            message = assistant,
            intent = "COMPARISON",
            queryTarget = "DOCTOR",
            nextAction = "SHOW_CATALOG",
            comparisonRequest = previous
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "clarification")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, capture(contextualHistory)) } returns content
        every {
            catalog.promptEvidence(
                content,
                capture(searchQuery),
                capture(searchQuery),
                AgentQueryTarget.DOCTOR,
                "COMPARISON",
                content
            )
        } answers {
            if (secondArg<String>().contains("张医生")) oldEvidence else comparisonEvidence()
        }
        every { catalog.filterComparisonEvidence(any(), any()) } answers {
            comparisonFilter().filterComparisonEvidence(firstArg(), secondArg())
        }

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertTrue(contextualHistory.captured.isEmpty())
        assertEquals(content, searchQuery.captured)
        assertTrue(completed.captured.comparisonRequest?.operands.orEmpty().isEmpty())
        assertEquals(setOf(ComparisonMissingField.OPERANDS), completed.captured.comparisonRequest?.missingFields)
        assertEquals("", completed.captured.modelName)
        verify(exactly = 0) { fixture.turnService.projectMessage(assistant) }
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `project detail price ignores an intervening human handoff summary`() {
        val content = "多少钱？"
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(
                summary = """{"schemaVersion":1,"focusSemanticsVersion":1,"unresolvedTopics":["HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION"]}""",
                contextType = "PROJECT",
                contextId = "project-1"
            )
        )
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(
                focusSemanticsVersion = 1,
                unresolvedTopics = listOf("HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION")
            ),
            emptyList()
        )
        every { fixture.contextBuilder.serializeSummary(any()) } returns
            """{"schemaVersion":1,"focusSemanticsVersion":1,"unresolvedTopics":["HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION"]}"""
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        every {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.PROJECT, any(), any())
        } returns AgentPromptEvidence()
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals("PROJECT", completed.captured.queryTarget)
        assertFalse(completed.captured.releaseDetailContext)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `history resolves an uncertain label before parser failure without replacing locked labels`() {
        val content = "I do not not want a doctor; compare Alpha Clinic and Beta Clinic"
        val history = listOf(message(1, "USER", "Show doctors"))
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(AgentSessionSummary(), history)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every { catalog.contextualSearchQuery(content, listOf("Show doctors")) } returns "$content Show doctors"
        val evidence = comparisonEvidence(
            comparisonItem("alpha", "Alpha Clinic"),
            comparisonItem("beta", "Beta Clinic")
        )
        every {
            catalog.promptEvidence(
                content,
                "$content Show doctors",
                "$content Show doctors",
                AgentQueryTarget.INSTITUTION,
                "COMPARISON",
                content
            )
        } returns evidence
        every { catalog.filterComparisonEvidence(evidence, any()) } returns evidence
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withException(SocketTimeoutException("intent parser timed out")))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().string(containsString("请求动作：COMPARISON")))
            .andExpect(content().string(containsString("请求对象：INSTITUTION,DOCTOR")))
            .andExpect(content().string(not(containsString("待澄清对象：DOCTOR"))))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("COMPARISON", completed.captured.intent)
        assertEquals("INSTITUTION", completed.captured.queryTarget)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `ambiguous history candidate requires parser instead of becoming locked context`() {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, "对比一下")
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(
            AgentSessionSummary(),
            listOf(message(1, "USER", "对比医生和机构"))
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.contextualSearchQuery("对比一下", listOf("对比医生和机构")) } returns "对比一下 对比医生和机构"
        every {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, "COMPARISON", any())
        } returns comparisonEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = "对比一下"))

        assertEquals("COMPARISON", completed.captured.intent)
        assertEquals("INSTITUTION", completed.captured.queryTarget)
        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `complete high confidence route does not call the intent parser`() {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val chat = fixture.chat
        prepareChatGeneration(fixture, "对比上海的热玛吉机构")
        every { catalog.hasInstitutionProjectMatch("对比上海的热玛吉机构") } returns false
        every { catalog.contextualSearchQuery("对比上海的热玛吉机构", emptyList()) } returns "对比上海的热玛吉机构"
        every {
            catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION, "COMPARISON", any())
        } returns comparisonEvidence()

        chat.sendMessage("session-1", "user-1", SendMessageRequest(content = "对比上海的热玛吉机构"))

        intentServer.verify()
        completionServer.verify()
    }

    @Test
    fun `begins one running turn with canonical request hash and user message`() {
        val session = session()
        val savedTurn = slot<AgentTurnEntity>()
        val savedMessage = slot<ChatMessageEntity>()
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns null
        every { turns.findBySessionIdAndStatus("session-1", AgentTurnStatus.RUNNING) } returns null
        every { turns.save(capture(savedTurn)) } answers { savedTurn.captured }
        every { messages.save(capture(savedMessage)) } answers { savedMessage.captured }
        every { sessions.save(any()) } answers { firstArg() }

        val result = lifecycle.beginTurn("session-1", "user-1", "  hello  ", "key-1")

        assertEquals(BeginTurnResult.Started::class, result::class)
        result as BeginTurnResult.Started
        assertEquals(1, result.sequenceNo)
        assertEquals(2, session.nextSequenceNo)
        assertEquals(AgentTurnStatus.RUNNING, savedTurn.captured.status)
        assertEquals(LocalDateTime.now(clock), savedTurn.captured.startedAt)
        assertEquals(LocalDateTime.now(clock), savedTurn.captured.createdAt)
        assertEquals(LocalDateTime.now(clock).plus(turnLease), savedTurn.captured.leaseExpiresAt)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", savedTurn.captured.requestHash)
        assertEquals(1, savedMessage.captured.sequenceNo)
        assertEquals("USER", savedMessage.captured.role)
        assertEquals("hello", savedMessage.captured.content)
        assertEquals(LocalDateTime.now(clock), savedMessage.captured.createdAt)
        assertEquals(LocalDateTime.now(clock), session.updatedAt)
    }

    @Test
    fun `replays completed turn exactly from assistant metadata without a new message`() {
        val turn = turn(status = AgentTurnStatus.SUCCEEDED)
        val replayed = ChatMessageEntity(
            sessionId = "session-1", turnId = turn.id, sequenceNo = 2, role = "ASSISTANT", content = "answer",
            metadataJson = """{"intent":"CATALOG_QA","queryTarget":"PROJECT","nextAction":"SHOW_CATALOG","catalogItems":[],"catalogReport":null}"""
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns turn
        every { messages.findByTurnIdAndRole(turn.id, "ASSISTANT") } returns replayed
        every { messages.findByTurnIdAndRole(turn.id, "USER") } returns message(1, "USER", "hello")

        val result = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1")

        assertTrue(result is BeginTurnResult.Replayed)
        val replay = (result as BeginTurnResult.Replayed).turn
        assertEquals("answer", replay.message.content)
        assertEquals("CATALOG_QA", replay.intent)
        assertEquals("PROJECT", replay.queryTarget)
        assertEquals("SHOW_CATALOG", replay.nextAction)
        verify(exactly = 0) { messages.save(any()) }
    }

    @Test
    fun `replay projects historical planning content and metadata without rewriting stored message`() {
        val turn = turn(status = AgentTurnStatus.SUCCEEDED)
        val unsafeItem = AgentCatalogItemResponse(
            type = "PROJECT",
            id = "123e4567-e89b-12d3-a456-426614174000",
            name = "Project A",
            subtitle = "one day downtime",
            summary = "pain free and risk free",
            attributes = linkedMapOf("reference price" to "$888", "risk" to "none")
        )
        val unsafeReport = AgentCatalogReportResponse(
            mode = "PLANNING",
            title = "Best treatment plan",
            summary = "Guaranteed result",
            items = listOf(unsafeItem),
            comparisonDimensions = listOf("risk"),
            warnings = listOf("no warning")
        )
        val storedContent = "Choose Project A because it is perfect for you"
        val replayed = ChatMessageEntity(
            sessionId = "session-1",
            turnId = turn.id,
            sequenceNo = 2,
            role = "ASSISTANT",
            content = storedContent,
            metadataJson = objectMapper.writeValueAsString(
                mapOf(
                    "intent" to "PLANNING",
                    "queryTarget" to "PROJECT",
                    "nextAction" to "START_PLANNING",
                    "catalogItems" to listOf(unsafeItem),
                    "catalogReport" to unsafeReport
                )
            )
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns turn
        every { messages.findByTurnIdAndRole(turn.id, "ASSISTANT") } returns replayed
        every { messages.findByTurnIdAndRole(turn.id, "USER") } returns message(1, "USER", "hello")

        val previousLocale = LocaleContextHolder.getLocale()
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        try {
            val result = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1") as BeginTurnResult.Replayed

            assertEquals(
                "This is platform information reference only and is not diagnosis or treatment advice. " +
                    "The platform can show structured details such as names and prices; personal suitability, " +
                    "downtime, pain, contraindications, and risks require confirmation with the institution or a qualified clinician.",
                result.turn.message.content
            )
            assertEquals(storedContent, replayed.content)
            assertEquals("", result.turn.catalogItems.single().subtitle)
            assertEquals("", result.turn.catalogItems.single().summary)
            assertEquals(mapOf("reference price" to "$888"), result.turn.catalogItems.single().attributes)
            assertEquals("SUMMARY", result.turn.catalogReport?.mode)
            assertEquals(emptyList<String>(), result.turn.catalogReport?.comparisonDimensions)
            assertFalse(result.turn.catalogReport?.title.orEmpty().contains("Best treatment plan"))
            assertFalse(result.turn.catalogReport?.summary.orEmpty().contains("Guaranteed result"))
            verify(exactly = 0) { messages.save(any()) }
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
    }

    @Test
    fun `rejects an idempotency key reused with a different request`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns turn(requestHash = "not-the-hash")

        assertThrows(IdempotencyKeyConflictException::class.java) {
            lifecycle.beginTurn("session-1", "user-1", "hello", "key-1")
        }
    }

    @Test
    fun `active running turn remains in progress`() {
        val running = turn(status = AgentTurnStatus.RUNNING).apply {
            leaseExpiresAt = LocalDateTime.now(clock).plusSeconds(1)
        }
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns running

        assertEquals(BeginTurnResult.InProgress, lifecycle.beginTurn("session-1", "user-1", "hello", "key-1"))
        assertEquals(AgentTurnStatus.RUNNING, running.status)
        verify(exactly = 0) { turns.save(any()) }
    }

    @Test
    fun `expired running turn is failed and a successor starts`() {
        val now = LocalDateTime.now(clock)
        val expired = turn(status = AgentTurnStatus.RUNNING).apply {
            idempotencyKey = "old-key"
            startedAt = now.minusSeconds(5)
            leaseExpiresAt = now
        }
        val savedTurns = mutableListOf<AgentTurnEntity>()
        val currentSession = session()
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns currentSession
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "new-key") } returns null
        every { turns.findBySessionIdAndStatus("session-1", AgentTurnStatus.RUNNING) } returns expired
        every { turns.save(capture(savedTurns)) } answers { firstArg() }
        every { turns.flush() } just runs
        every { messages.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }

        val result = lifecycle.beginTurn("session-1", "user-1", "hello", "new-key")

        assertTrue(result is BeginTurnResult.Started)
        val successor = savedTurns.last()
        assertEquals(AgentTurnStatus.FAILED, expired.status)
        assertEquals("STALE_RECOVERED", expired.errorCode)
        assertEquals(now, expired.completedAt)
        assertEquals(5_000, expired.totalDurationMs)
        assertEquals(AgentTurnStatus.RUNNING, successor.status)
        assertEquals(now.plus(turnLease), successor.leaseExpiresAt)
        verify(exactly = 1) { turns.flush() }
    }

    @Test
    fun `same key stale recovery remains idempotency expired on every retry`() {
        val now = LocalDateTime.now(clock)
        val expired = turn(status = AgentTurnStatus.RUNNING).apply {
            startedAt = now.minusSeconds(5)
            leaseExpiresAt = now
        }
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns expired
        every { turns.save(expired) } returns expired
        every { turns.flush() } just runs

        val first = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1")
        val second = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1")

        assertEquals(BeginTurnResult.IdempotencyExpired, first)
        assertEquals(BeginTurnResult.IdempotencyExpired, second)
        assertEquals(AgentTurnStatus.FAILED, expired.status)
        assertEquals("STALE_RECOVERED", expired.errorCode)
        verify(exactly = 1) { turns.save(expired) }
        verify(exactly = 1) { turns.flush() }
    }

    @Test
    fun `marks successful turn complete once with replay metadata`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
        every { turns.findSessionIdById(running.id) } returns "session-1"
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(capture(assistant)) } answers { assistant.captured }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val result = lifecycle.completeTurn(CompleteTurnCommand(running.id, "final answer", "CATALOG_QA", "PROJECT", "SHOW_CATALOG"))

        assertEquals("final answer", result.message.content)
        assertEquals(2, assistant.captured.sequenceNo)
        assertTrue(assistant.captured.metadataJson.contains("CATALOG_QA"))
        assertEquals(AgentTurnStatus.SUCCEEDED, running.status)
        assertEquals(LocalDateTime.now(clock), running.completedAt)
        assertEquals(LocalDateTime.now(clock), assistant.captured.createdAt)
        verifyOrder {
            turns.findSessionIdById(running.id)
            sessions.findByIdForUpdate("session-1")
            turns.findByIdForUpdate(running.id)
        }
    }

    @Test
    fun `successful topic switch releases the persisted detail session context`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val ownedSession = session(contextType = "INSTITUTION", contextId = "institution-1")
        every { turns.findSessionIdById(running.id) } returns ownedSession.id
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate(ownedSession.id) } returns ownedSession
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(any()) } answers { firstArg() }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId(ownedSession.id) } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        lifecycle.completeTurn(
            CompleteTurnCommand(
                turnId = running.id,
                content = "new topic",
                intent = "CATALOG_QA",
                queryTarget = "DOCTOR",
                nextAction = "SHOW_CATALOG",
                releaseDetailContext = true
            )
        )

        assertEquals("GENERAL", ownedSession.contextType)
        assertEquals("", ownedSession.contextId)
    }

    @Test
    fun `human consultation completion persists intent target action and institution cards`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
        val institution = humanInstitutionItem()
        every { turns.findSessionIdById(running.id) } returns "session-1"
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(capture(assistant)) } answers { assistant.captured }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val result = lifecycle.completeTurn(
            CompleteTurnCommand(
                turnId = running.id,
                content = "Choose an institution to continue.",
                intent = "HUMAN_CONSULTATION",
                queryTarget = "INSTITUTION",
                nextAction = "SELECT_INSTITUTION",
                catalogItems = listOf(institution),
                conversationFocusUpdate = ConversationFocusUpdate.PRESERVE
            )
        )

        val metadata = objectMapper.readTree(assistant.captured.metadataJson)
        assertEquals("HUMAN_CONSULTATION", metadata.path("intent").asText())
        assertEquals("INSTITUTION", metadata.path("queryTarget").asText())
        assertEquals("SELECT_INSTITUTION", metadata.path("nextAction").asText())
        assertEquals("PRESERVE", metadata.path("conversationFocusUpdate").asText())
        assertEquals(ConversationFocusUpdate.PRESERVE, result.message.let(lifecycle::projectMessage).conversationFocusUpdate)
        assertEquals(institution, result.catalogItems.single())
        assertEquals(institution.id, metadata.path("catalogItems").single().path("institutionId").asText())
        assertTrue(metadata.path("catalogItems").single().path("canChatWithHuman").asBoolean())
    }

    @Test
    fun `idempotent replay restores the same human consultation card snapshot`() {
        val completed = turn(status = AgentTurnStatus.SUCCEEDED)
        val institution = humanInstitutionItem()
        val assistant = message(2, "ASSISTANT", "Choose an institution to continue.").apply {
            turnId = completed.id
            metadataJson = objectMapper.writeValueAsString(
                linkedMapOf(
                    "intent" to "HUMAN_CONSULTATION",
                    "queryTarget" to "INSTITUTION",
                    "nextAction" to "SELECT_INSTITUTION",
                    "catalogItems" to listOf(institution),
                    "catalogReport" to null
                )
            )
        }
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns completed
        every { messages.findByTurnIdAndRole(completed.id, "ASSISTANT") } returns assistant
        every { messages.findByTurnIdAndRole(completed.id, "USER") } returns message(1, "USER", "hello")

        val replay = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1") as BeginTurnResult.Replayed

        assertEquals("HUMAN_CONSULTATION", replay.turn.intent)
        assertEquals("INSTITUTION", replay.turn.queryTarget)
        assertEquals("SELECT_INSTITUTION", replay.turn.nextAction)
        assertEquals(listOf(institution), replay.turn.catalogItems)
        verify(exactly = 0) { messages.save(any()) }
        verify(exactly = 0) { turns.save(any()) }
        verify(exactly = 0) { sessions.save(any()) }

        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        every { fixture.availabilityGuard.requireGenerationEnabled() } just runs
        every { fixture.turnService.beginTurn("session-1", "user-1", "hello", "key-1") } returns replay

        val replayedByChat = fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest("hello", "key-1"))

        assertEquals(listOf(institution), replayedByChat.catalogItems)
        verify(exactly = 0) { fixture.turnService.completeTurn(any()) }
        verify(exactly = 0) { catalog.selectConsultableInstitutions(any(), any(), any()) }
        completionServer.verify()
        intentServer.verify()
    }

    @Test
    fun `completion persists normalized comparison request and report in assistant metadata`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
        val report = comparisonReport()
        every { turns.findSessionIdById(running.id) } returns "session-1"
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(capture(assistant)) } answers { assistant.captured }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val result = lifecycle.completeTurn(
            CompleteTurnCommand(
                turnId = running.id,
                content = "comparison",
                intent = "COMPARISON",
                queryTarget = "PROJECT",
                nextAction = "SHOW_COMPARISON",
                catalogReport = report,
                comparisonRequest = unnormalizedComparisonRequest()
            )
        )

        val metadata = objectMapper.readTree(assistant.captured.metadataJson)
        val storedRequest = metadata.path("comparisonRequest")
        assertEquals(setOf("alpha", "beta"), storedRequest.path("operands").map { it.path("entityId").asText() }.toSet())
        assertEquals(setOf("PRICE", "RATING"), storedRequest.path("dimensions").map { it.asText() }.toSet())
        assertEquals("Shanghai", storedRequest.path("constraints").path("city").asText())
        assertEquals("2000", storedRequest.path("constraints").path("budgetMax").asText())
        assertFalse(storedRequest.path("constraints").has("medical"))
        assertTrue(storedRequest.path("missingFields").isEmpty)
        assertFalse(storedRequest.has("isComplete"))
        assertEquals("Comparison", metadata.path("catalogReport").path("title").asText())
        assertEquals("COMPARISON", result.catalogReport?.mode)
        assertEquals("Comparison", result.catalogReport?.title)
        assertEquals(setOf("PRICE", "RATING"), result.catalogReport?.comparisonDimensions?.toSet())
        assertEquals(setOf("alpha", "beta"), result.comparisonRequest?.operands?.map { it.entityId }?.toSet())
        assertEquals(setOf("PRICE", "RATING"), result.comparisonRequest?.dimensions?.toSet())
        assertTrue(result.comparisonRequest?.missingFields?.isEmpty() == true)
    }

    @Test
    fun `non comparison completion discards comparison request before persistence`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
        every { turns.findSessionIdById(running.id) } returns "session-1"
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(capture(assistant)) } answers { assistant.captured }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val result = lifecycle.completeTurn(
            CompleteTurnCommand(
                turnId = running.id,
                content = "planning",
                intent = "PLANNING",
                queryTarget = "PROJECT",
                nextAction = "START_PLANNING",
                comparisonRequest = unnormalizedComparisonRequest()
            )
        )

        val metadata = objectMapper.readTree(assistant.captured.metadataJson)
        assertTrue(metadata.path("comparisonRequest").isNull)
        assertEquals(null, result.comparisonRequest)
    }

    @Test
    fun `idempotent replay restores the same normalized comparison request and report`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
        val report = comparisonReport()
        every { turns.findSessionIdById(running.id) } returns "session-1"
        every { turns.findByIdForUpdate(running.id) } returns running
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns null
        every { messages.save(capture(assistant)) } answers { assistant.captured }
        every { turns.save(any()) } answers { firstArg() }
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val completed = lifecycle.completeTurn(
            CompleteTurnCommand(
                running.id,
                "comparison",
                "COMPARISON",
                "PROJECT",
                "SHOW_COMPARISON",
                catalogReport = report,
                comparisonRequest = unnormalizedComparisonRequest()
            )
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns running
        every { messages.findByTurnIdAndRole(running.id, "ASSISTANT") } returns assistant.captured
        every { messages.findByTurnIdAndRole(running.id, "USER") } returns message(1, "USER", "hello")

        val replayed = lifecycle.beginTurn("session-1", "user-1", "hello", "key-1") as BeginTurnResult.Replayed

        assertEquals(
            completed.comparisonRequest?.operands?.map { it.entityId }?.toSet(),
            replayed.turn.comparisonRequest?.operands?.map { it.entityId }?.toSet()
        )
        assertEquals(completed.comparisonRequest?.dimensions?.toSet(), replayed.turn.comparisonRequest?.dimensions?.toSet())
        assertEquals(completed.comparisonRequest?.constraints, replayed.turn.comparisonRequest?.constraints)
        assertEquals(completed.comparisonRequest?.missingFields, replayed.turn.comparisonRequest?.missingFields)
        assertEquals(completed.catalogReport?.mode, replayed.turn.catalogReport?.mode)
        assertEquals(completed.catalogReport?.title, replayed.turn.catalogReport?.title)
        assertEquals(
            completed.catalogReport?.comparisonDimensions?.toSet(),
            replayed.turn.catalogReport?.comparisonDimensions?.toSet()
        )
        assertEquals("trace-1", replayed.turn.traceId)
        verify(exactly = 1) { messages.save(any()) }
    }

    @Test
    fun `malformed comparison request does not discard a valid catalog report`() {
        val report = comparisonReport()
        val assistant = message(2, "ASSISTANT", "comparison").apply {
            metadataJson = objectMapper.writeValueAsString(
                mapOf(
                    "intent" to "COMPARISON",
                    "comparisonRequest" to "not-an-object",
                    "catalogReport" to report
                )
            )
        }

        val projection = lifecycle.projectMessage(assistant)

        assertEquals(null, projection.comparisonRequest)
        assertEquals("COMPARISON", projection.catalogReport?.mode)
        assertEquals("Comparison", projection.catalogReport?.title)
        assertEquals(setOf("PRICE", "RATING"), projection.catalogReport?.comparisonDimensions?.toSet())
    }

    @Test
    fun `comparison request normalization failure does not discard a valid catalog report`() {
        val report = comparisonReport()
        val assistant = message(2, "ASSISTANT", "comparison").apply {
            metadataJson = """{
                "intent":"COMPARISON",
                "comparisonRequest":{
                    "operands":[null],
                    "targetType":"PROJECT",
                    "dimensions":[],
                    "constraints":{},
                    "missingFields":[]
                },
                "catalogReport":${objectMapper.writeValueAsString(report)}
            }""".trimIndent()
        }

        val projection = lifecycle.projectMessage(assistant)

        assertEquals(null, projection.comparisonRequest)
        assertEquals("COMPARISON", projection.catalogReport?.mode)
        assertEquals("Comparison", projection.catalogReport?.title)
        assertEquals(setOf("PRICE", "RATING"), projection.catalogReport?.comparisonDimensions?.toSet())
    }

    @Test
    fun `planning projection uses one metadata parse path`() {
        val countingMapper = CountingObjectMapper().apply { registerKotlinModule() }
        val countingLifecycle = TurnLifecycleService(
            sessions,
            messages,
            turns,
            context,
            countingMapper,
            clock = clock,
            turnLease = turnLease,
            comparisonRequestBuilder = ComparisonRequestBuilder()
        )
        val unsafeItem = AgentCatalogItemResponse(
            type = "PROJECT",
            id = "project-1",
            name = "Project One",
            subtitle = "one day downtime",
            summary = "pain free",
            attributes = linkedMapOf("reference price" to "$888", "risk" to "none")
        )
        val assistant = message(2, "ASSISTANT", "unsafe planning claim").apply {
            metadataJson = countingMapper.writeValueAsString(
                mapOf(
                    "intent" to " PLANNING ",
                    "queryTarget" to "PROJECT",
                    "nextAction" to "START_PLANNING",
                    "conversationFocusUpdate" to "SET",
                    "catalogItems" to listOf(unsafeItem),
                    "catalogReport" to null
                )
            )
        }

        val projection = countingLifecycle.projectMessage(assistant)
        val projectedMetadata = objectMapper.readTree(projection.message.metadataJson)

        assertEquals(1, countingMapper.readTreeCallCount)
        assertEquals("PLANNING", projection.intent)
        assertNotEquals("unsafe planning claim", projection.message.content)
        assertEquals("PLANNING", projectedMetadata.path("intent").asText())
        assertEquals("SET", projectedMetadata.path("conversationFocusUpdate").asText())
        assertEquals("", projectedMetadata.path("catalogItems").single().path("subtitle").asText())
        assertFalse(projectedMetadata.has("comparisonRequest"))
        assertEquals("", projection.catalogItems.single().subtitle)
        assertEquals("", projection.catalogItems.single().summary)
        assertEquals(mapOf("reference price" to "$888"), projection.catalogItems.single().attributes)
    }

    @Test
    fun `malformed catalog report does not discard a valid comparison request`() {
        val assistant = message(2, "ASSISTANT", "comparison").apply {
            metadataJson = objectMapper.writeValueAsString(
                mapOf(
                    "intent" to "COMPARISON",
                    "comparisonRequest" to unnormalizedComparisonRequest(),
                    "catalogReport" to "not-an-object"
                )
            )
        }

        val projection = lifecycle.projectMessage(assistant)

        assertEquals(setOf("alpha", "beta"), projection.comparisonRequest?.operands?.map { it.entityId }?.toSet())
        assertEquals(setOf("PRICE", "RATING"), projection.comparisonRequest?.dimensions?.toSet())
        assertEquals(null, projection.catalogReport)
    }

    @Test
    fun `legacy and malformed root metadata reconstruct with backward compatible defaults`() {
        val legacyTurn = turn(id = "legacy-turn", status = AgentTurnStatus.SUCCEEDED)
        val malformedTurn = turn(id = "malformed-turn", status = AgentTurnStatus.SUCCEEDED)
        val legacy = message(2, "ASSISTANT", "legacy").apply { metadataJson = "{}" }
        val malformed = message(4, "ASSISTANT", "malformed").apply { metadataJson = "{not-json" }
        every { turns.findSessionIdById(any()) } returns "session-1"
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { turns.findByIdForUpdate("legacy-turn") } returns legacyTurn
        every { turns.findByIdForUpdate("malformed-turn") } returns malformedTurn
        every { messages.findByTurnIdAndRole("legacy-turn", "ASSISTANT") } returns legacy
        every { messages.findByTurnIdAndRole("malformed-turn", "ASSISTANT") } returns malformed

        val reconstructed = listOf(
            lifecycle.completeTurn(CompleteTurnCommand("legacy-turn", "ignored", "GENERAL_CHAT", null, "NONE")),
            lifecycle.completeTurn(CompleteTurnCommand("malformed-turn", "ignored", "GENERAL_CHAT", null, "NONE"))
        )

        reconstructed.forEach { turn ->
            assertEquals("GENERAL_CHAT", turn.intent)
            assertEquals(null, turn.queryTarget)
            assertEquals("NONE", turn.nextAction)
            assertTrue(turn.catalogItems.isEmpty())
            assertEquals(null, turn.comparisonRequest)
            assertEquals(null, turn.catalogReport)
        }
    }

    @Test
    fun `does not duplicate assistant message when completion is repeated`() {
        val succeeded = turn(status = AgentTurnStatus.SUCCEEDED)
        val assistant = ChatMessageEntity(sessionId = "session-1", turnId = succeeded.id, sequenceNo = 2, role = "ASSISTANT", content = "final")
        every { turns.findSessionIdById(succeeded.id) } returns "session-1"
        every { sessions.findByIdForUpdate("session-1") } returns session()
        every { turns.findByIdForUpdate(succeeded.id) } returns succeeded
        every { messages.findByTurnIdAndRole(succeeded.id, "ASSISTANT") } returns assistant

        val result = lifecycle.completeTurn(CompleteTurnCommand(succeeded.id, "ignored", "GENERAL_CHAT", null, "NONE"))

        assertEquals("final", result.message.content)
        verify(exactly = 0) { messages.save(any()) }
    }

    @Test
    fun `fails and cancels running turns in independent transactions`() {
        val failed = turn(status = AgentTurnStatus.RUNNING)
        val cancelled = turn(id = "turn-2", status = AgentTurnStatus.RUNNING)
        every { turns.findByIdForUpdate("turn-1") } returns failed
        every { turns.findByIdForUpdate("turn-2") } returns cancelled
        every { turns.save(any()) } answers { firstArg() }

        lifecycle.failTurn("turn-1", "MODEL_TIMEOUT", 125)
        lifecycle.cancelTurn("turn-2", "CLIENT_CANCELLED", 75)

        assertEquals(AgentTurnStatus.FAILED, failed.status)
        assertEquals("MODEL_TIMEOUT", failed.errorCode)
        assertEquals(AgentTurnStatus.CANCELLED, cancelled.status)
        assertEquals("CLIENT_CANCELLED", cancelled.errorCode)
        assertEquals(LocalDateTime.now(clock), failed.completedAt)
        assertEquals(LocalDateTime.now(clock), cancelled.completedAt)
        val failTransaction = TurnLifecycleService::class.java.declaredMethods.first { it.name == "failTurn" }
            .getAnnotation(Transactional::class.java)
        val cancelTransaction = TurnLifecycleService::class.java.declaredMethods.first { it.name == "cancelTurn" }
            .getAnnotation(Transactional::class.java)
        assertEquals(Propagation.REQUIRES_NEW, failTransaction.propagation)
        assertEquals(Propagation.REQUIRES_NEW, cancelTransaction.propagation)
    }

    @Test
    fun `clearing history writes the injected clock time`() {
        val session = session()
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session
        every { messages.deleteBySessionId("session-1") } just runs
        every { messages.flush() } just runs
        every { turns.deleteBySessionId("session-1") } just runs
        every { turns.flush() } just runs
        every { sessions.save(session) } returns session

        lifecycle.clearHistory("session-1", "user-1")

        assertEquals(LocalDateTime.now(clock), session.updatedAt)
    }

    @Test
    fun `returns expired replay when successful assistant message was pruned`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns turn(status = AgentTurnStatus.SUCCEEDED)
        every { messages.findByTurnIdAndRole(any(), "ASSISTANT") } returns null
        every { messages.findByTurnIdAndRole(any(), "USER") } returns message(1, "USER", "hello")

        assertEquals(BeginTurnResult.IdempotencyExpired, lifecycle.beginTurn("session-1", "user-1", "hello", "key-1"))
    }

    @Test
    fun `loads summary before succeeded messages while respecting count and token limits`() {
        val session = session(summary = """{"schemaVersion":1,"goals":["glow"],"lastSummarizedSequence":2}""")
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(
            message(1, "USER", "one two"), message(2, "ASSISTANT", "three four"), message(3, "USER", "five six")
        )

        val loaded = context.load("user-1", "session-1", maxMessages = 2, maxTokens = 20)

        assertEquals(AgentSessionSummary(schemaVersion = 1, lastSummarizedSequence = 2), loaded.summary)
        assertEquals(listOf("ASSISTANT", "USER"), loaded.messages.map { it.role })
        assertEquals(listOf("three four", "five six"), loaded.messages.map { it.content })
    }

    @Test
    fun `does not expose unfinished turns or another users session`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-2") } returns null

        assertThrows(IllegalArgumentException::class.java) { context.load("user-2", "session-1", 20, 100) }
    }

    @Test
    fun `does not load succeeded messages older than the configured retention window`() {
        val owned = session()
        val expired = message(1, "USER", "expired", LocalDateTime.now(clock).minusDays(8))
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns owned
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } returns listOf(
            expired,
            message(2, "ASSISTANT", "current")
        )
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(
            expired,
            message(2, "ASSISTANT", "current")
        )

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertEquals(listOf("current"), loaded.messages.map { it.content })
        verify { sessions.save(owned) }
        verify { messages.deleteAll(match { it.toList().contains(expired) }) }
    }

    @Test
    fun `uses the shared clock for retention boundary ordering and summary timestamps`() {
        val owned = session(summary = "{\"schemaVersion\":1,\"lastSummarizedSequence\":1}")
        val cutoff = LocalDateTime.of(2026, 8, 3, 4, 0)
        val expired = message(1, "USER", "expired", cutoff.minusNanos(1))
        val boundary = message(2, "ASSISTANT", "boundary", cutoff)
        val current = message(3, "USER", "current", LocalDateTime.of(2026, 8, 10, 3, 59))
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns owned
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } returns listOf(current, expired, boundary)
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(current, expired, boundary)

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertEquals(listOf("boundary", "current"), loaded.messages.map { it.content })
        assertEquals(LocalDateTime.of(2026, 8, 10, 4, 0), owned.summaryUpdatedAt)
        assertEquals(LocalDateTime.of(2026, 8, 10, 4, 0), owned.updatedAt)
        verify { messages.deleteAll(match { it.toList() == listOf(expired) }) }
    }

    @Test
    fun `history load returns a safe copy of historical planning content and metadata`() {
        val storedContent = "Choose Project A because it is perfect for you"
        val historical = message(2, "ASSISTANT", storedContent).apply {
            metadataJson = """{
                "intent":"PLANNING",
                "queryTarget":"PROJECT",
                "nextAction":"START_PLANNING",
                "conversationFocusUpdate":"SET",
                "catalogItems":[{
                    "type":"PROJECT",
                    "id":"123e4567-e89b-12d3-a456-426614174000",
                    "name":"Project A",
                    "subtitle":"one day downtime",
                    "summary":"pain free and risk free",
                    "attributes":{"reference price":"${'$'}888","risk":"none"},
                    "institutionId":null,
                    "projectId":null,
                    "canChatWithHuman":false
                }],
                "catalogReport":null
            }""".trimIndent()
        }
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } returns listOf(historical)
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(historical)

        val previousLocale = LocaleContextHolder.getLocale()
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        try {
            val loaded = context.load("user-1", "session-1", 20, 1_000).messages.single()
            val projectedMetadata = objectMapper.readTree(loaded.metadataJson)

            assertEquals(
                "This is platform information reference only and is not diagnosis or treatment advice. " +
                    "The platform can show structured details such as names and prices; personal suitability, " +
                    "downtime, pain, contraindications, and risks require confirmation with the institution or a qualified clinician.",
                loaded.content
            )
            assertEquals("", projectedMetadata.path("catalogItems").single().path("subtitle").asText())
            assertEquals("", projectedMetadata.path("catalogItems").single().path("summary").asText())
            assertEquals("SET", projectedMetadata.path("conversationFocusUpdate").asText())
            assertEquals("$888", projectedMetadata.path("catalogItems").single().path("attributes").path("reference price").asText())
            assertFalse(projectedMetadata.path("catalogItems").single().path("attributes").has("risk"))
            assertEquals(storedContent, historical.content)
            assertTrue(historical.metadataJson.contains("one day downtime"))
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
    }

    @Test
    fun `physically prunes expired failed turn messages while keeping context succeeded only`() {
        val owned = session()
        val failedUser = message(1, "USER", "failed request", LocalDateTime.now(clock).minusDays(8))
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns owned
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } returns listOf(failedUser)
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertTrue(loaded.messages.isEmpty())
        verify { messages.deleteAll(match { it.toList() == listOf(failedUser) }) }
    }

    @Test
    fun `drops polluted stored summary fields and entity references`() {
        val polluted = session(
            """{"schemaVersion":1,"goals":["pregnant and allergic"],"preferences":["risk detail"],"constraints":["raw health"],"unresolvedTopics":["FREE_TEXT","CATALOG_QA:PROJECT:SHOW_CATALOG"],"entityRefs":{"UNTRUSTED":["risk"],"PROJECT":["not-a-uuid","123e4567-e89b-12d3-a456-426614174000"]}}"""
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns polluted
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertEquals(
            AgentSessionSummary(
                entityRefs = mapOf("PROJECT" to listOf("123e4567-e89b-12d3-a456-426614174000")),
                unresolvedTopics = listOf("CATALOG_QA:PROJECT:SHOW_CATALOG")
            ),
            loaded.summary
        )
        assertFalse(polluted.summaryJson.contains("pregnant"))
        assertFalse(polluted.summaryJson.contains("UNTRUSTED"))
    }

    @Test
    fun `drops every field from an unsupported summary schema version`() {
        val unsupported = session(
            """{"schemaVersion":99,"unresolvedTopics":["CATALOG_QA:PROJECT:SHOW_CATALOG"],"entityRefs":{"PROJECT":["123e4567-e89b-12d3-a456-426614174000"]}}"""
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns unsupported
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        assertEquals(AgentSessionSummary(), context.load("user-1", "session-1", 20, 100).summary)
        assertFalse(unsupported.summaryJson.contains("123e4567"))
    }

    @Test
    fun `drops malformed structured topic slot orders`() {
        val malformed = session(
            """{"schemaVersion":1,"unresolvedTopics":["CATALOG_QA:SHOW_CATALOG:START_PLANNING","GENERAL_CHAT:PROJECT:INSTITUTION","CATALOG_QA:PROJECT:SHOW_CATALOG"]}"""
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns malformed
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        assertEquals(listOf("CATALOG_QA:PROJECT:SHOW_CATALOG"), context.load("user-1", "session-1", 20, 100).summary.unresolvedTopics)
    }

    @Test
    fun `summary refreshes repeated topic recency so a general boundary stays latest`() {
        val ownedSession = session(
            objectMapper.writeValueAsString(
                AgentSessionSummary(
                    focusSemanticsVersion = 1,
                    unresolvedTopics = listOf(
                        "GENERAL_CHAT",
                        "CATALOG_QA:DOCTOR:SHOW_CATALOG"
                    )
                )
            )
        )
        every { sessions.save(ownedSession) } returns ownedSession

        context.updateSummaryAndPrune(
            session = ownedSession,
            sequenceNo = 3,
            intent = "GENERAL_CHAT",
            queryTarget = null,
            nextAction = "NONE",
            catalogItems = emptyList()
        )

        val topics = objectMapper.readTree(ownedSession.summaryJson)
            .path("unresolvedTopics")
            .map { it.asText() }
        assertEquals(
            listOf("CATALOG_QA:DOCTOR:SHOW_CATALOG", "GENERAL_CHAT"),
            topics
        )
    }

    @Test
    fun `summary focus updates preserve overlays and record explicit clears`() {
        val ownedSession = session(
            objectMapper.writeValueAsString(
                AgentSessionSummary(unresolvedTopics = listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"))
            ),
            contextType = "INSTITUTION",
            contextId = "institution-1"
        )
        every { sessions.save(ownedSession) } returns ownedSession

        context.updateSummaryAndPrune(
            session = ownedSession,
            sequenceNo = 2,
            intent = "GENERAL_CHAT",
            queryTarget = null,
            nextAction = "NONE",
            catalogItems = emptyList(),
            conversationFocusUpdate = ConversationFocusUpdate.PRESERVE
        )

        assertEquals(
            listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
            objectMapper.readTree(ownedSession.summaryJson).path("unresolvedTopics").map { it.asText() }
        )
        assertEquals(1, objectMapper.readTree(ownedSession.summaryJson).path("focusSemanticsVersion").asInt())

        context.updateSummaryAndPrune(
            session = ownedSession,
            sequenceNo = 3,
            intent = "SAFETY_SCREENING",
            queryTarget = null,
            nextAction = "NONE",
            catalogItems = emptyList(),
            conversationFocusUpdate = ConversationFocusUpdate.CLEAR
        )

        assertEquals(
            listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG", "GENERAL_CHAT"),
            objectMapper.readTree(ownedSession.summaryJson).path("unresolvedTopics").map { it.asText() }
        )
    }

    @Test
    fun `focus semantics upgrade does not certify a legacy social general topic as a boundary`() {
        val ownedSession = session(
            objectMapper.writeValueAsString(
                AgentSessionSummary(
                    unresolvedTopics = listOf(
                        "CATALOG_QA:INSTITUTION:SHOW_CATALOG",
                        "GENERAL_CHAT"
                    ),
                    lastSummarizedSequence = 1
                )
            ),
            contextType = "INSTITUTION",
            contextId = "institution-1"
        )
        every { sessions.save(ownedSession) } returns ownedSession

        context.updateSummaryAndPrune(
            session = ownedSession,
            sequenceNo = 2,
            intent = "CATALOG_QA",
            queryTarget = "INSTITUTION",
            nextAction = "SHOW_CATALOG",
            catalogItems = emptyList(),
            conversationFocusUpdate = ConversationFocusUpdate.PRESERVE
        )

        val migrated = objectMapper.readTree(ownedSession.summaryJson)
        assertEquals(1, migrated.path("focusSemanticsVersion").asInt())
        assertEquals(
            listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
            migrated.path("unresolvedTopics").map { it.asText() }
        )
    }

    @Test
    fun `focus semantics upgrade preserves an active detail scope across target changes`() {
        val ownedSession = session(
            objectMapper.writeValueAsString(AgentSessionSummary()),
            contextType = "INSTITUTION",
            contextId = "institution-1"
        )
        every { sessions.save(ownedSession) } returns ownedSession

        context.updateSummaryAndPrune(
            session = ownedSession,
            sequenceNo = 1,
            intent = "CATALOG_QA",
            queryTarget = "PROJECT",
            nextAction = "SHOW_CATALOG",
            catalogItems = emptyList(),
            conversationFocusUpdate = ConversationFocusUpdate.PRESERVE
        )

        val migrated = objectMapper.readTree(ownedSession.summaryJson)
        assertEquals(1, migrated.path("focusSemanticsVersion").asInt())
        assertEquals(
            listOf("CATALOG_QA:INSTITUTION:SHOW_CATALOG"),
            migrated.path("unresolvedTopics").map { it.asText() }
        )
    }

    @Test
    fun `summary keeps only valid human consultation institution topics`() {
        val session = session(
            """{"schemaVersion":1,"unresolvedTopics":["HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION","HUMAN_CONSULTATION:INSTITUTION","HUMAN_CONSULTATION:DOCTOR:SELECT_INSTITUTION","HUMAN_CONSULTATION:INSTITUTION:SHOW_CATALOG"]}"""
        )
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertEquals(
            listOf(
                "HUMAN_CONSULTATION:INSTITUTION:SELECT_INSTITUTION",
                "HUMAN_CONSULTATION:INSTITUTION"
            ),
            loaded.summary.unresolvedTopics
        )
    }

    @Test
    fun `uses a character budget for chinese and no space content`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(
            message(1, "USER", "短消息"),
            message(2, "ASSISTANT", "这是没有空格但很长的中文消息"),
            message(3, "USER", "https://example.com/very-long-unbroken-url-path")
        )

        val loaded = context.load("user-1", "session-1", 20, 5)

        assertEquals(listOf("短消息"), loaded.messages.map { it.content })
    }

    @Test
    fun `pruning retains deterministic summary but excludes raw safety evidence`() {
        val session = session()
        val old = message(1, "USER", "I am pregnant and have severe allergies", LocalDateTime.now(clock).minusDays(8))
        val newer = message(2, "ASSISTANT", "safe response")
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(old, newer)
        every { messages.findBySessionIdOrderBySequenceNoAsc("session-1") } returns listOf(old, newer)
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) } just runs

        context.updateSummaryAndPrune(session, 2, "SAFETY_SCREENING", null, "NONE", emptyList())

        assertTrue(session.summaryJson.contains("SAFETY_SCREENING"))
        assertFalse(session.summaryJson.contains("pregnant"))
        verify { messages.deleteAll(match { it.toList().map { message -> message.id }.contains(old.id) }) }
    }

    @Test
    fun `does not prune any messages when summary validation fails`() {
        val corrupt = session(summary = "{not-json")

        assertThrows(IllegalArgumentException::class.java) {
            context.updateSummaryAndPrune(corrupt, 1, "GENERAL_CHAT", null, "NONE", emptyList())
        }

        verify(exactly = 0) { messages.deleteAll(any<Iterable<ChatMessageEntity>>()) }
    }

    @Test
    fun `keeps only validated router slots and known platform entity types in summary`() {
        val session = session()
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        context.updateSummaryAndPrune(
            session = session,
            sequenceNo = 1,
            intent = "untrusted health evidence",
            queryTarget = "unknown target",
            nextAction = "arbitrary action",
            catalogItems = listOf(
                com.joysong.server.agent.dto.AgentCatalogItemResponse(
                    type = "UNTRUSTED_TYPE", id = "untrusted-id", name = "", subtitle = "", summary = "",
                    attributes = emptyMap()
                )
            )
        )

        assertFalse(session.summaryJson.contains("untrusted"))
        assertFalse(session.summaryJson.contains("UNTRUSTED_TYPE"))
    }

    @Test
    fun `does not write incompatible allowlisted router slots into summary`() {
        val session = session()
        every { sessions.save(any()) } answers { firstArg() }
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns emptyList()

        context.updateSummaryAndPrune(session, 1, "GENERAL_CHAT", "PROJECT", "SHOW_CATALOG", emptyList())

        assertFalse(session.summaryJson.contains("GENERAL_CHAT:PROJECT:SHOW_CATALOG"))
    }

    private fun assertParserSafetyUpgrade(response: ResponseCreator) {
        val content = "I am not not pregnant; compare treatments"
        val router = AgentIntentRouter()
        val local = router.assessCurrent(content, "GENERAL")
        assertTrue(local.unresolvedSafetyNegation)
        assertTrue(local.intentEvidence.single { it.intent == AgentIntent.COMPARISON }.locked)
        assertTrue(local.targetEvidence.single { it.target == AgentQueryTarget.PROJECT }.locked)

        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, content)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.contextualSearchQuery(content, emptyList()) } returns content
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(response)
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = content))

        assertEquals("SAFETY_SCREENING", completed.captured.intent)
        assertEquals(null, completed.captured.queryTarget)
        intentServer.verify()
        completionServer.verify()
    }

    private fun assertParserFailureStillCompletes(response: ResponseCreator) {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val chat = fixture.chat
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, "我想改善脸部松弛")
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch("我想改善脸部松弛") } returns false
        every { catalog.contextualSearchQuery("我想改善脸部松弛", emptyList()) } returns "我想改善脸部松弛"
        every {
            catalog.promptEvidence(
                "我想改善脸部松弛",
                "我想改善脸部松弛",
                "我想改善脸部松弛",
                null,
                "AUTO",
                "我想改善脸部松弛"
            )
        } returns AgentPromptEvidence()
        every {
            catalog.promptEvidence(
                "我想改善脸部松弛",
                "我想改善脸部松弛 fixture-keyword",
                "我想改善脸部松弛 fixture-keyword",
                AgentQueryTarget.PROJECT,
                "AUTO",
                "我想改善脸部松弛"
            )
        } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(response)
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        chat.sendMessage("session-1", "user-1", SendMessageRequest(content = "我想改善脸部松弛"))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals(null, completed.captured.queryTarget)
        intentServer.verify()
        completionServer.verify()
    }

    private fun assertGenerationPromptLabels(
        userContent: String,
        expectedPrimaryIntent: String,
        expectedLabels: List<String>,
        absentLabels: List<String> = emptyList(),
        parserResponse: ResponseCreator? = null
    ) {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(fixture, userContent)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch(userContent) } returns false
        every { catalog.contextualSearchQuery(userContent, emptyList()) } returns userContent
        val evidence = if (expectedPrimaryIntent == "COMPARISON") {
            comparisonEvidence(
                comparisonItem("alpha", "Alpha", type = "PROJECT"),
                comparisonItem("beta", "Beta", type = "PROJECT")
            )
        } else AgentPromptEvidence()
        every { catalog.promptEvidence(any(), any(), any(), any(), any(), any()) } returns evidence
        if (expectedPrimaryIntent == "COMPARISON") {
            every { catalog.filterComparisonEvidence(evidence, any()) } returns evidence
        }
        parserResponse?.let { response ->
            intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(response)
        }
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .apply {
                expectedLabels.forEach { label -> andExpect(content().string(containsString(label))) }
                absentLabels.forEach { label -> andExpect(content().string(not(containsString(label)))) }
            }
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = userContent))

        assertEquals(expectedPrimaryIntent, completed.captured.intent)
        intentServer.verify()
        completionServer.verify()
    }

    private data class ChatFixture(
        val chat: ChatService,
        val turnService: TurnLifecycleService,
        val operationLogger: AgentOperationLogger,
        val contextBuilder: AgentContextBuilder,
        val availabilityGuard: AiAgentAvailabilityGuard
    )

    private data class DetailEntryChatFixture(
        val institution: InstitutionEntity,
        val completionServer: MockRestServiceServer,
        val intentServer: MockRestServiceServer,
        val catalog: AgentCatalogService,
        val fixture: ChatFixture,
        val completed: io.mockk.CapturingSlot<CompleteTurnCommand>
    )

    private fun detailEntryChatFixture(
        content: String,
        institutionName: String,
        answer: String,
        historyMessages: List<ChatMessageEntity> = emptyList(),
        summary: AgentSessionSummary = AgentSessionSummary()
    ): DetailEntryChatFixture {
        val institution = InstitutionEntity(
            id = "context-institution",
            name = institutionName,
            city = "杭州",
            description = "机构简介",
            isVerified = true
        )
        val institutionRepository = mockk<InstitutionRepository>()
        every { institutionRepository.findById(institution.id) } returns java.util.Optional.of(institution)
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(
            completionTemplate,
            intentTemplate,
            catalog,
            institutionRepository = institutionRepository
        )
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            content,
            session(contextType = "INSTITUTION", contextId = institution.id)
        )
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns
            AgentContext(summary, historyMessages)
        every { fixture.contextBuilder.serializeSummary(summary) } returns objectMapper.writeValueAsString(summary)
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = answer)
        )
        every { catalog.hasInstitutionProjectMatch(content) } returns false
        every {
            catalog.contextualSearchQuery(
                content,
                historyMessages.filter { it.role.equals("USER", true) }.map { it.content }.takeLast(4)
            )
        } returns content
        return DetailEntryChatFixture(institution, completionServer, intentServer, catalog, fixture, completed)
    }

    private fun chatFixture(
        completionTemplate: RestTemplate,
        intentTemplate: RestTemplate,
        catalog: AgentCatalogService,
        turnService: TurnLifecycleService = mockk(),
        contextBuilder: AgentContextBuilder = mockk(),
        projectRepository: ProjectRepository = mockk(),
        institutionRepository: InstitutionRepository = mockk(),
        institutionProjectRepository: InstitutionProjectRepository = mockk(),
        doctorRepository: DoctorRepository = mockk()
    ): ChatFixture {
        val operationLogger = mockk<AgentOperationLogger>()
        val availabilityGuard = mockk<AiAgentAvailabilityGuard>()
        return ChatFixture(
            chat = ChatService(
        sessionRepository = sessions,
        messageRepository = messages,
        projectRepository = projectRepository,
        institutionRepository = institutionRepository,
        institutionProjectRepository = institutionProjectRepository,
        doctorRepository = doctorRepository,
        doctorInstitutionService = mockk<DoctorInstitutionService>(),
        institutionProjectDetailResolver = mockk<InstitutionProjectDetailResolver>(),
        agentCatalogService = catalog,
        agentIntentRouter = AgentIntentRouter(),
        comparisonRequestBuilder = ComparisonRequestBuilder(),
        turnLifecycleService = turnService,
        agentOperationLogger = operationLogger,
        agentContextBuilder = contextBuilder,
        aiAgentAvailabilityGuard = availabilityGuard,
        objectMapper = objectMapper,
        restTemplate = completionTemplate,
        intentParserRestTemplate = intentTemplate,
        aiAgentProperties = AiAgentProperties(
            provider = AiAgentProvider.OPENAI_COMPATIBLE,
            apiKey = "test-key",
            baseUrl = "https://provider.test/v1",
            model = "answer-model",
            intentModel = "intent-small"
        )
            ),
            turnService = turnService,
            operationLogger = operationLogger,
            contextBuilder = contextBuilder,
            availabilityGuard = availabilityGuard
        )
    }

    private fun prepareChatGeneration(
        fixture: ChatFixture,
        content: String,
        ownedSession: ChatSessionEntity = session()
    ) {
        every { fixture.availabilityGuard.requireGenerationEnabled() } just runs
        every { fixture.turnService.beginTurn("session-1", "user-1", content, any()) } returns
            BeginTurnResult.Started("turn-1", "trace-1", 1)
        every { fixture.turnService.completeTurn(any()) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { fixture.operationLogger.completed(any(), any(), any(), any(), any()) } just runs
        every { fixture.contextBuilder.load("user-1", "session-1", 20, 4_000) } returns AgentContext(AgentSessionSummary(), emptyList())
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-1") } returns ownedSession
    }

    private fun session(
        summary: String = "{}",
        contextType: String = "GENERAL",
        contextId: String = ""
    ) = ChatSessionEntity(
        id = "session-1",
        userId = "user-1",
        persona = "CONSULTANT",
        contextType = contextType,
        contextId = contextId,
        summaryJson = summary
    )

    private fun turn(
        id: String = "turn-1",
        status: AgentTurnStatus = AgentTurnStatus.PENDING,
        requestHash: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    ) = AgentTurnEntity(id = id, sessionId = "session-1", sequenceNo = 1, idempotencyKey = "key-1", requestHash = requestHash, status = status, traceId = "trace-1")

    private fun message(sequenceNo: Long, role: String, content: String, createdAt: LocalDateTime = LocalDateTime.now()) =
        ChatMessageEntity(id = "message-$sequenceNo", sessionId = "session-1", sequenceNo = sequenceNo, role = role, content = content, createdAt = createdAt)

    private fun humanInstitutionItem() = AgentCatalogItemResponse(
        type = "INSTITUTION",
        id = "123e4567-e89b-12d3-a456-426614174000",
        name = "Harmony Clinic",
        subtitle = "Shanghai",
        summary = "",
        attributes = emptyMap(),
        institutionId = "123e4567-e89b-12d3-a456-426614174000",
        canChatWithHuman = true
    )

    private fun singleItemEvidence(
        title: String,
        item: AgentCatalogItemResponse
    ) = AgentPromptEvidence(
        context = "平台$title：${item.name}",
        report = AgentCatalogReportResponse(
            mode = "AUTO",
            title = title,
            summary = "",
            items = listOf(item),
            comparisonDimensions = emptyList(),
            warnings = emptyList()
        )
    )

    private fun unnormalizedComparisonRequest() = ComparisonRequest(
        operands = listOf(
            ComparisonOperand(AgentQueryTarget.PROJECT, " alpha ", " Alpha "),
            ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Duplicate Alpha"),
            ComparisonOperand(AgentQueryTarget.PROJECT, "beta", "Beta"),
            ComparisonOperand(AgentQueryTarget.DOCTOR, "doctor", "Doctor")
        ),
        targetType = AgentQueryTarget.PROJECT,
        dimensions = listOf(" price ", "risk", "RATING", "PRICE"),
        constraints = linkedMapOf("city" to " Shanghai ", "budgetMax" to " 2000 ", "medical" to "secret"),
        missingFields = setOf(ComparisonMissingField.TARGET_TYPE, ComparisonMissingField.OPERANDS)
    )

    private fun comparisonReport() = AgentCatalogReportResponse(
        mode = "COMPARISON",
        title = "Comparison",
        summary = "Compared safely",
        items = emptyList(),
        comparisonDimensions = listOf("PRICE", "RATING"),
        warnings = emptyList()
    )

    private fun comparisonEvidence(
        vararg items: AgentCatalogItemResponse,
        totalMatched: Int = items.size
    ) = AgentPromptEvidence(
        context = items.joinToString("; ") { it.name },
        report = AgentCatalogReportResponse(
            mode = "COMPARISON",
            title = "Comparison",
            summary = "Comparison evidence",
            items = items.toList(),
            comparisonDimensions = listOf("Rating", "Reviews"),
            warnings = emptyList(),
            totalMatched = totalMatched
        )
    )

    private fun comparisonItem(id: String, name: String, type: String = "INSTITUTION") = AgentCatalogItemResponse(
        type = type,
        id = id,
        name = name,
        subtitle = "Shanghai",
        summary = "",
        attributes = linkedMapOf("Rating" to "4.8", "Reviews" to "100")
    )

    private fun comparisonFilter() = AgentCatalogService(
        institutionRepository = mockk(relaxed = true),
        doctorRepository = mockk(relaxed = true),
        projectRepository = mockk(relaxed = true),
        institutionProjectRepository = mockk(relaxed = true),
        doctorProjectRepository = mockk<DoctorProjectRepository>(relaxed = true),
        discoverSearchService = mockk<DiscoverSearchService>(relaxed = true),
        doctorInstitutionService = mockk(relaxed = true),
        institutionProjectDetailResolver = InstitutionProjectDetailResolver(),
        institutionConsultantService = mockk<InstitutionConsultantService>(relaxed = true),
        agentProfileService = mockk<AgentProfileService>(relaxed = true)
    )

    private class CountingObjectMapper : ObjectMapper() {
        var readTreeCallCount = 0

        override fun readTree(content: String): JsonNode {
            readTreeCallCount += 1
            return super.readTree(content)
        }
    }
}
