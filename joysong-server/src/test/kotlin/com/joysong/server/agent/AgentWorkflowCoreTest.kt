package com.joysong.server.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.context.AgentContext
import com.joysong.server.agent.context.AgentSessionSummary
import com.joysong.server.agent.diagnostics.AgentOperationLogger
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.AiAgentAvailabilityGuard
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.IdempotencyKeyConflictException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.agent.service.AgentCatalogService
import com.joysong.server.agent.service.AgentIntent
import com.joysong.server.agent.service.AgentIntentRouter
import com.joysong.server.agent.service.AgentRouteAssessment
import com.joysong.server.agent.service.ParsedAgentRoute
import com.joysong.server.agent.service.AgentPromptEvidence
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatService
import com.joysong.server.chat.service.ChatTurnResult
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

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
        turnLease = turnLease
    )

    @BeforeEach
    fun defaultMessageCleanupCandidates() {
        every { messages.findBySessionIdOrderBySequenceNoAsc(any()) } returns emptyList()
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
            turnLifecycleService = turnService,
            agentOperationLogger = operationLogger,
            agentContextBuilder = contextBuilder,
            aiAgentAvailabilityGuard = availabilityGuard,
            objectMapper = objectMapper,
            restTemplate = completionTemplate,
            intentParserRestTemplate = intentTemplate,
            aiAgentProperties = AiAgentProperties(
                apiKey = "test-key",
                baseUrl = "https://provider.test/v1",
                model = "answer-model",
                intentModel = "intent-small"
            ),
            fastReasoningEffort = "",
            complexReasoningEffort = ""
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
                AgentQueryTarget.PROJECT
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
    fun `invalid optional intents member keeps local route and still completes`() {
        assertParserFailureStillCompletes(
            withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"CATALOG_QA\",\"intents\":[\"CATALOG_QA\",\"NOT_ALLOWED\"],\"queryTarget\":\"PROJECT\",\"keywords\":[\"fixture-keyword\"]}"}}]}""",
                MediaType.APPLICATION_JSON
            )
        )
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
    fun `first turn in project context completes locally without intent parser`() {
        val completionTemplate = RestTemplate()
        val intentTemplate = RestTemplate()
        val completionServer = MockRestServiceServer.bindTo(completionTemplate).build()
        val intentServer = MockRestServiceServer.bindTo(intentTemplate).build()
        val catalog = mockk<AgentCatalogService>()
        val fixture = chatFixture(completionTemplate, intentTemplate, catalog)
        val completed = slot<CompleteTurnCommand>()
        prepareChatGeneration(
            fixture,
            "恢复期多久",
            session(contextType = "PROJECT", contextId = "project-1")
        )
        every { fixture.turnService.completeTurn(capture(completed)) } returns ChatTurnResult(
            ChatMessageEntity(sessionId = "session-1", role = "ASSISTANT", content = "answer")
        )
        every { catalog.hasInstitutionProjectMatch("恢复期多久") } returns false
        every { catalog.contextualSearchQuery("恢复期多久", emptyList()) } returns "恢复期多久"
        every { catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.PROJECT) } returns AgentPromptEvidence()
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

        fixture.chat.sendMessage("session-1", "user-1", SendMessageRequest(content = "恢复期多久"))

        assertEquals("CATALOG_QA", completed.captured.intent)
        assertEquals("PROJECT", completed.captured.queryTarget)
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
        every { catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION) } returns AgentPromptEvidence()
        intentServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess(
                """{"choices":[{"message":{"content":"{\"intent\":\"COMPARISON\",\"queryTarget\":\"INSTITUTION\",\"keywords\":[]}"}}]}""",
                MediaType.APPLICATION_JSON
            ))
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

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
        every { catalog.promptEvidence(any(), any(), any(), AgentQueryTarget.INSTITUTION) } returns AgentPromptEvidence()
        completionServer.expect(requestTo("https://provider.test/v1/chat/completions"))
            .andExpect(content().json("""{"model":"answer-model"}""", false))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"answer"}}]}""", MediaType.APPLICATION_JSON))

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
                null
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

    private data class ChatFixture(
        val chat: ChatService,
        val turnService: TurnLifecycleService,
        val operationLogger: AgentOperationLogger,
        val contextBuilder: AgentContextBuilder,
        val availabilityGuard: AiAgentAvailabilityGuard
    )

    private fun chatFixture(
        completionTemplate: RestTemplate,
        intentTemplate: RestTemplate,
        catalog: AgentCatalogService
    ): ChatFixture {
        val turnService = mockk<TurnLifecycleService>()
        val operationLogger = mockk<AgentOperationLogger>()
        val contextBuilder = mockk<AgentContextBuilder>()
        val availabilityGuard = mockk<AiAgentAvailabilityGuard>()
        return ChatFixture(
            chat = ChatService(
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
        turnLifecycleService = turnService,
        agentOperationLogger = operationLogger,
        agentContextBuilder = contextBuilder,
        aiAgentAvailabilityGuard = availabilityGuard,
        objectMapper = objectMapper,
        restTemplate = completionTemplate,
        intentParserRestTemplate = intentTemplate,
        aiAgentProperties = AiAgentProperties(
            apiKey = "test-key",
            baseUrl = "https://provider.test/v1",
            model = "answer-model",
            intentModel = "intent-small"
        ),
        fastReasoningEffort = "",
        complexReasoningEffort = ""
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
}
