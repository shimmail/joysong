package com.joysong.server.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.diagnostics.AgentOperationLogger
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatService
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestTemplate
import org.slf4j.LoggerFactory
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Tag("mysql-integration")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = ["spring.task.scheduling.enabled=false"]
)
class AgentChatFlowIntegrationTest {

    @Autowired
    private lateinit var chatService: ChatService

    @Autowired
    private lateinit var agentOperationLogger: AgentOperationLogger

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var messageRepository: ChatMessageRepository

    @Autowired
    private lateinit var turnRepository: AgentTurnRepository

    @Autowired
    private lateinit var sessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var turnLifecycleService: TurnLifecycleService

    @Autowired
    private lateinit var aiAgentProperties: AiAgentProperties

    @Autowired
    @Qualifier("agentLlmRestTemplate")
    private lateinit var llmRestTemplate: RestTemplate

    private val transactionStates = CopyOnWriteArrayList<Boolean>()
    private lateinit var transactionInterceptor: ClientHttpRequestInterceptor

    @BeforeEach
    fun installTransactionObserver() {
        fakeLlmCalls.set(0)
        fakeLlmStatus.set(200)
        fakeLlmDelayMs.set(0)
        fakeLlmContent.set("测试回复")
        fakeLlmRawResponse.set(null)
        fakeIntentParserContent.set("""{"intent":"CATALOG_QA","queryTarget":"DOCTOR","keywords":["context"]}""")
        fakeLlmRequestBodies.clear()
        aiAgentProperties.intentParserEnabled = false
        aiAgentProperties.intentModel = "intent-test-model"
        transactionStates.clear()
        llmRestTemplate.requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(1_000)
            setReadTimeout(100)
        }
        transactionInterceptor = ClientHttpRequestInterceptor { request, body, execution ->
            transactionStates += TransactionSynchronizationManager.isActualTransactionActive()
            execution.execute(request, body)
        }
        llmRestTemplate.interceptors = llmRestTemplate.interceptors + transactionInterceptor
    }

    @AfterEach
    fun removeTransactionObserver() {
        llmRestTemplate.interceptors = llmRestTemplate.interceptors.filterNot { it === transactionInterceptor }
        assertEquals(0, turnRepository.findAll().count { it.status == AgentTurnStatus.RUNNING })
    }

    @Test
    fun `successful synchronous turn is persisted once and replayed without a transaction around the model call`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val first = chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("请介绍一下", "idem-success-1")
        )
        val replay = chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("请介绍一下", "idem-success-1")
        )

        assertEquals("测试回复", first.message.content)
        assertEquals(first.message.id, replay.message.id)
        assertNotNull(first.traceId)
        assertEquals(first.traceId, replay.traceId)
        assertEquals(1, fakeLlmCalls.get())
        assertEquals(1, turnCount(session.id))
        assertEquals(
            listOf(1L, 2L),
            messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id).map { it.sequenceNo }
        )
        assertEquals(AgentTurnStatus.SUCCEEDED, turnRepository.findAll().single { it.sessionId == session.id }.status)
        assertEquals(listOf(false), transactionStates)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
    }

    @Test
    fun `ambiguous request uses intent model and does not override its current institution target from history`() {
        aiAgentProperties.intentParserEnabled = true
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("请比较医生", "intent-history-doctor-1")
        )
        fakeLlmCalls.set(0)
        fakeLlmRequestBodies.clear()

        val result = chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("这家机构怎么样", "intent-current-institution-1")
        )

        assertEquals("CATALOG_QA", result.intent)
        assertEquals("INSTITUTION", result.queryTarget)
        assertEquals(2, fakeLlmCalls.get())
        val requests = fakeLlmRequestBodies.map(objectMapper::readTree)
        assertEquals("intent-test-model", requests.first().path("model").asText())
        assertEquals("test-model", requests.last().path("model").asText())
    }

    @Test
    fun `parser failure still completes using the local route`() {
        aiAgentProperties.intentParserEnabled = true
        fakeIntentParserContent.set("not-json")
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val result = chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("我最近感觉脸垮了，该怎么办", "intent-parser-fallback-1")
        )

        assertEquals("GENERAL_CHAT", result.intent)
        assertEquals(2, fakeLlmCalls.get())
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `session list projects historical planning last message without rewriting it`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val started = turnLifecycleService.beginTurn(
            session.id,
            "user-1",
            "legacy planning request",
            "legacy-planning-session-list"
        ) as BeginTurnResult.Started
        val turn = turnRepository.findById(started.turnId).orElseThrow().apply {
            status = AgentTurnStatus.SUCCEEDED
            completedAt = LocalDateTime.now()
        }
        turnRepository.saveAndFlush(turn)
        val unsafeContent = "Choose Project A because it is perfect for you"
        val historical = messageRepository.saveAndFlush(
            ChatMessageEntity(
                sessionId = session.id,
                turnId = turn.id,
                sequenceNo = started.sequenceNo * 2,
                role = "ASSISTANT",
                content = unsafeContent,
                metadataJson = """{
                    "intent":"PLANNING",
                    "queryTarget":"PROJECT",
                    "nextAction":"START_PLANNING",
                    "catalogItems":[],
                    "catalogReport":null
                }""".trimIndent(),
                createdAt = LocalDateTime.now().plusSeconds(1)
            )
        )

        val response = mockMvc.perform(
            get("/api/chat/sessions")
                .header("Accept-Language", "en")
        ).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val listed = objectMapper.readTree(response).path("data")
            .first { it.path("id").asText() == session.id }

        assertEquals(
            "This is platform information reference only and is not diagnosis or treatment advice. " +
                "The platform can show structured details such as names and prices; personal suitability, " +
                "downtime, pain, contraindications, and risks require confirmation with the institution or a qualified clinician.",
            listed.path("lastMessage").asText()
        )
        assertEquals(unsafeContent, messageRepository.findById(historical.id).orElseThrow().content)

        val generalSession = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val generalContent = "Ordinary catalog answer"
        messageRepository.saveAndFlush(
            ChatMessageEntity(
                sessionId = generalSession.id,
                sequenceNo = 1,
                role = "ASSISTANT",
                content = generalContent,
                metadataJson = """{"intent":"CATALOG_QA","catalogItems":[],"catalogReport":null}"""
            )
        )
        assertEquals(generalContent, chatService.getLastMessage(generalSession.id))
    }

    @Test
    fun `planning prompt limits responses to transparent information reference`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        chatService.sendMessage(
            session.id,
            "user-1",
            SendMessageRequest("帮我规划适合自己的项目", "planning-policy-1")
        )

        val requestBody = fakeLlmRequestBodies.single()
        assertTrue(requestBody.contains("仅作为信息参考"))
        assertTrue(requestBody.contains("不构成诊断或治疗建议"))
        assertTrue(requestBody.contains("不得声称“最适合”“为你制定”或已结合恢复期、疼痛偏好完成排序"))
        assertTrue(requestBody.contains("不得从简介、宣传语或详情自由文本推断恢复期、疼痛、禁忌或风险事实"))
        assertTrue(requestBody.contains("需向机构确认"))
    }

    @Test
    fun `unsafe planning output is replaced and catalog free text is not grounded`() {
        val project = projectRepository.save(
            ProjectEntity(
                id = "planning-safety-project",
                name = "规划安全边界项目",
                category = "肤质管理",
                tags = "规划安全边界",
                description = "目录宣称恢复期1天并且无痛",
                slogan = "目录宣称零风险且最适合你",
                detailContent = "<p>目录宣称无需确认禁忌</p>"
            )
        )
        val bypassReply = "结合你可接受3天恢复和低痛偏好，A排在第一位，建议选择A"
        fakeLlmContent.set(bypassReply)

        try {
            val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

            val result = chatService.sendMessage(
                session.id,
                "user-1",
                SendMessageRequest("帮我规划规划安全边界项目", "planning-safety-1")
            )

            assertEquals("PLANNING", result.intent)
            val requestBody = fakeLlmRequestBodies.single()
            assertTrue(requestBody.contains(project.id))
            assertTrue(requestBody.contains(project.name))
            assertFalse(requestBody.contains("目录宣称恢复期1天"))
            assertFalse(requestBody.contains("目录宣称零风险"))
            assertFalse(requestBody.contains("目录宣称无需确认禁忌"))
            assertTrue(result.catalogItems.any { it.id == project.id && it.name == project.name })

            val safeReply = result.message.content
            assertTrue(safeReply.contains("信息参考"))
            assertTrue(safeReply.contains("不构成诊断或治疗建议"))
            assertTrue(safeReply.contains("需向机构确认"))
            assertFalse(safeReply.contains(bypassReply))
            listOf("可接受3天恢复", "低痛偏好", "排在第一位", "建议选择A").forEach {
                assertFalse(safeReply.contains(it))
            }
            assertEquals(
                safeReply,
                messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id).last().content
            )
        } finally {
            projectRepository.deleteById(project.id)
        }
    }

    @Test
    fun `clearing history removes turns and messages and resets the retained session`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(session.id, "user-1", SendMessageRequest("first", "clear-history-1"))

        turnLifecycleService.clearHistory(session.id, "user-1")

        assertEquals(0, messageCount(session.id))
        assertEquals(0, turnCount(session.id))
        val retained = sessionRepository.findById(session.id).orElseThrow()
        assertEquals(1, retained.nextSequenceNo)
        assertEquals("{}", retained.summaryJson)
    }

    @Test
    fun `deleting one turn removes both of its messages without affecting another user`() {
        val owned = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val other = chatService.createSession("user-2", CreateSessionRequest(persona = "CONSULTANT"))
        val ownedTurn = chatService.sendMessage(owned.id, "user-1", SendMessageRequest("owned", "delete-turn-1"))
        chatService.sendMessage(other.id, "user-2", SendMessageRequest("other", "delete-turn-2"))

        turnLifecycleService.deleteTurn(ownedTurn.message.id, "user-1")

        assertEquals(0, messageCount(owned.id))
        assertEquals(0, turnCount(owned.id))
        assertEquals(2, messageCount(other.id))
        assertEquals(1, turnCount(other.id))
    }

    @Test
    fun `deleting a session removes its history before soft deletion without affecting another user`() {
        val owned = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val other = chatService.createSession("user-2", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(owned.id, "user-1", SendMessageRequest("owned", "delete-session-1"))
        chatService.sendMessage(other.id, "user-2", SendMessageRequest("other", "delete-session-2"))

        turnLifecycleService.deleteSession(owned.id, "user-1")

        assertEquals(null, sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(owned.id, "user-1"))
        assertEquals(0, messageCount(owned.id))
        assertEquals(0, turnCount(owned.id))
        assertNotNull(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(other.id, "user-2"))
        assertEquals(2, messageCount(other.id))
        assertEquals(1, turnCount(other.id))
    }

    @Test
    fun `clearing normalized persona sessions is scoped to that user and persona`() {
        val ownedConsultant = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val ownedBestie = chatService.createSession("user-1", CreateSessionRequest(persona = "BESTIE"))
        val otherConsultant = chatService.createSession("user-2", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(ownedConsultant.id, "user-1", SendMessageRequest("owned consultant", "clear-sessions-1"))
        chatService.sendMessage(ownedBestie.id, "user-1", SendMessageRequest("owned bestie", "clear-sessions-2"))
        chatService.sendMessage(otherConsultant.id, "user-2", SendMessageRequest("other consultant", "clear-sessions-3"))

        turnLifecycleService.clearSessions("user-1", " consultant ")

        assertEquals(null, sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(ownedConsultant.id, "user-1"))
        assertEquals(0, messageCount(ownedConsultant.id))
        assertEquals(0, turnCount(ownedConsultant.id))
        assertNotNull(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(ownedBestie.id, "user-1"))
        assertEquals(2, messageCount(ownedBestie.id))
        assertEquals(1, turnCount(ownedBestie.id))
        assertNotNull(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(otherConsultant.id, "user-2"))
        assertEquals(2, messageCount(otherConsultant.id))
        assertEquals(1, turnCount(otherConsultant.id))
    }

    @Test
    fun `provider failure marks the turn failed with a stable traced error and hides its user message from history`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        fakeLlmStatus.set(500)

        val error = assertThrows(AgentChatException::class.java) {
            chatService.sendMessage(session.id, "user-1", SendMessageRequest("provider failure", "provider-failure-1"))
        }

        assertEquals("AI_PROVIDER_UNAVAILABLE", error.code)
        assertNotNull(error.traceId)
        val failed = turnRepository.findAll().single { it.sessionId == session.id }
        assertEquals(AgentTurnStatus.FAILED, failed.status)
        assertEquals("AI_PROVIDER_UNAVAILABLE", failed.errorCode)
        assertEquals(listOf(1L), messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id).map { it.sequenceNo })
        assertEquals(emptyList<Long>(), chatService.getMessages(session.id, "user-1").map { it.sequenceNo })
    }

    @Test
    fun `history ignores before and returns only the requested recent successful messages`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(session.id, "user-1", SendMessageRequest("first", "history-1"))
        chatService.sendMessage(session.id, "user-1", SendMessageRequest("second", "history-2"))

        val oldCursor = LocalDateTime.of(2000, 1, 1, 0, 0)
        val all = chatService.getMessages(session.id, "user-1", limit = 100, before = oldCursor)
        val latest = chatService.getMessages(session.id, "user-1", limit = 1, before = oldCursor)

        assertEquals(listOf(1L, 2L, 3L, 4L), all.map { it.sequenceNo })
        assertEquals(listOf(4L), latest.map { it.sequenceNo })
    }

    @Test
    fun `idempotency conflict is exposed as a stable domain error without another model call`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(session.id, "user-1", SendMessageRequest("first", "conflict-1"))

        val error = assertThrows(AgentChatException::class.java) {
            chatService.sendMessage(session.id, "user-1", SendMessageRequest("different", "conflict-1"))
        }

        assertEquals("IDEMPOTENCY_KEY_CONFLICT", error.code)
        assertEquals(1, fakeLlmCalls.get())
        assertEquals(1, turnCount(session.id))
    }

    @Test
    fun `running and expired replay states are exposed as stable domain errors`() {
        val runningSession = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val running = turnLifecycleService.beginTurn(runningSession.id, "user-1", "running", "running-manual-1")
            as BeginTurnResult.Started

        val inProgress = try {
            assertThrows(AgentChatException::class.java) {
                chatService.sendMessage(runningSession.id, "user-1", SendMessageRequest("next", "running-next-1"))
            }
        } finally {
            turnLifecycleService.failTurn(running.turnId, "TEST_FIXTURE_FINISHED", 0)
        }
        assertEquals("TURN_IN_PROGRESS", inProgress.code)
        assertEquals(
            0,
            turnRepository.findAll().count { it.sessionId == runningSession.id && it.status == AgentTurnStatus.RUNNING }
        )

        val replaySession = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val completed = chatService.sendMessage(replaySession.id, "user-1", SendMessageRequest("completed", "expired-1"))
        messageRepository.deleteById(completed.message.id)
        val expired = assertThrows(AgentChatException::class.java) {
            chatService.sendMessage(replaySession.id, "user-1", SendMessageRequest("completed", "expired-1"))
        }
        assertEquals("IDEMPOTENCY_EXPIRED", expired.code)
    }

    @Test
    fun `missing session and invalid idempotency keys fail before calling the model`() {
        val missing = assertThrows(AgentChatException::class.java) {
            chatService.sendMessage("missing", "user-1", SendMessageRequest("hello", "missing-1"))
        }
        assertEquals("SESSION_NOT_FOUND", missing.code)

        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        listOf("   ", "x".repeat(101)).forEach { key ->
            val invalid = assertThrows(IllegalArgumentException::class.java) {
                chatService.sendMessage(session.id, "user-1", SendMessageRequest("hello", key))
            }
            assertEquals("INVALID_IDEMPOTENCY_KEY", invalid.message)
        }
        assertEquals(0, fakeLlmCalls.get())
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send returns the synchronous reply and trace id`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"请介绍一下","idempotencyKey":"http-1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.message.content").value("测试回复"))
            .andExpect(jsonPath("$.data.traceId").isNotEmpty)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send is rejected as AGENT_DISABLED before turn persistence when AI agent is disabled`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val turnsBefore = turnRepository.count()
        val enabledBefore = aiAgentProperties.enabled
        aiAgentProperties.enabled = false

        try {
            mockMvc.perform(
                post("/api/chat/sessions/{id}/messages", session.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"disabled request","idempotencyKey":"http-disabled-1"}""")
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("AGENT_DISABLED"))
        } finally {
            aiAgentProperties.enabled = enabledBefore
        }

        assertEquals(turnsBefore, turnRepository.count())
        assertEquals(0, fakeLlmCalls.get())
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send returns 404 for an unknown session`() {
        mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", "missing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"hello","idempotencyKey":"http-missing-1"}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("SESSION_NOT_FOUND"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send returns 409 when an idempotency key is reused for different content`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        chatService.sendMessage(session.id, "user-1", SendMessageRequest("first", "http-conflict-1"))

        mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"different","idempotencyKey":"http-conflict-1"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("IDEMPOTENCY_KEY_CONFLICT"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send returns 409 while another turn is running`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val running = turnLifecycleService.beginTurn(session.id, "user-1", "running", "http-running-1")
            as BeginTurnResult.Started

        try {
            mockMvc.perform(
                post("/api/chat/sessions/{id}/messages", session.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"next","idempotencyKey":"http-running-2"}""")
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.message").value("TURN_IN_PROGRESS"))
        } finally {
            turnLifecycleService.failTurn(running.turnId, "TEST_FIXTURE_FINISHED", 0)
        }
    }

    @ParameterizedTest(name = "provider status {0} is sanitized")
    @ValueSource(ints = [401, 429, 500, 503])
    @WithMockUser(username = "user-1")
    fun `HTTP provider status failures share one stable redacted contract`(providerStatus: Int) {
        fakeLlmStatus.set(providerStatus)

        assertHttpProviderFailure(
            idempotencyKey = "http-provider-status-$providerStatus",
            expectedErrorCode = "AI_PROVIDER_UNAVAILABLE",
            sensitiveValues = listOf("provider-secret-body", "private@example.com", "13800000000")
        )
    }

    @ParameterizedTest(name = "provider payload {index} is sanitized")
    @ValueSource(
        strings = [
            "{malformed-provider-secret-body test-key 13800000000 private@example.com",
            "{\"choices\":[],\"provider_marker\":\"provider-secret-body test-key 13800000000 private@example.com\"}"
        ]
    )
    @WithMockUser(username = "user-1")
    fun `HTTP malformed or empty provider payloads share one stable redacted contract`(rawResponse: String) {
        fakeLlmRawResponse.set(rawResponse)

        assertHttpProviderFailure(
            idempotencyKey = "http-provider-payload-${rawResponse.hashCode()}",
            expectedErrorCode = "AI_PROVIDER_UNAVAILABLE",
            sensitiveValues = listOf(rawResponse, "provider-secret-body", "private@example.com", "13800000000")
        )
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP send maps provider timeout to a redacted 503 response`() {
        fakeLlmDelayMs.set(500)

        assertHttpProviderFailure(
            idempotencyKey = "http-timeout-1",
            expectedErrorCode = "AI_PROVIDER_TIMEOUT",
            sensitiveValues = listOf("provider-secret-body")
        )
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP unknown failure persists logs and returns the same internal error code`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        fakeLlmContent.set("x".repeat(70_000))

        val logs = captureAgentOperationLogs {
            mockMvc.perform(
                post("/api/chat/sessions/{id}/messages", session.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"trigger database limit","idempotencyKey":"http-internal-1"}""")
            )
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.message").value("AGENT_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty)
        }

        val failed = turnRepository.findAll().single { it.sessionId == session.id }
        assertEquals(AgentTurnStatus.FAILED, failed.status)
        assertEquals("AGENT_INTERNAL_ERROR", failed.errorCode)
        val failureLog = logs.single { it.contains("operation=MODEL_COMPLETION") }
        assertTrue(failureLog.contains("terminalStatus=FAILED"))
        assertTrue(failureLog.contains("errorCode=AGENT_INTERNAL_ERROR"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP streaming endpoint is disabled synchronously without calling the model`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val callsBefore = fakeLlmCalls.get()

        mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"do not stream","idempotencyKey":"http-stream-1"}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("AGENT_STREAMING_DISABLED"))

        assertEquals(callsBefore, fakeLlmCalls.get())
        assertEquals(0, messageCount(session.id))
        assertEquals(0, turnCount(session.id))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `cross-user history deletion and clearing all hide session existence`() {
        val other = chatService.createSession("user-2", CreateSessionRequest(persona = "CONSULTANT"))

        listOf(
            get("/api/chat/sessions/{id}/messages", other.id),
            delete("/api/chat/sessions/{id}", other.id),
            delete("/api/chat/sessions/{id}/messages", other.id)
        ).forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.message").value("SESSION_NOT_FOUND"))
        }

        assertNotNull(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(other.id, "user-2"))
    }

    @Test
    fun `successful completion logs one redacted terminal operation and replay does not duplicate it`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val sensitiveContent = "糖尿病 prompt test-key 13800000000 private@example.com Authorization Bearer raw-token"
        lateinit var traceId: String

        val logs = captureAgentOperationLogs {
            val first = chatService.sendMessage(
                session.id,
                "user-1",
                SendMessageRequest(sensitiveContent, "log-success-1")
            )
            traceId = requireNotNull(first.traceId)
            val replay = chatService.sendMessage(
                session.id,
                "user-1",
                SendMessageRequest(sensitiveContent, "log-success-1")
            )
            assertEquals(first.message.id, replay.message.id)
        }

        val completionLogs = logs.filter { it.contains("operation=MODEL_COMPLETION") }
        assertEquals(1, completionLogs.size)
        val log = completionLogs.single()
        val turn = turnRepository.findAll().single { it.sessionId == session.id }
        assertTrue(log.contains("traceId=$traceId"))
        assertTrue(log.contains("turnId=${turn.id}"))
        assertTrue(log.contains("terminalStatus=SUCCEEDED"))
        assertTrue(log.contains("modelName=test-model"))
        assertTrue(Regex("""durationMs=\d+""").containsMatchIn(log))
        assertTrue(Regex("""sessionHash=[0-9a-f]{16}""").containsMatchIn(log))
        assertNoSensitiveLogData(log, sensitiveContent)
    }

    @Test
    fun `provider failure logs one redacted terminal operation with stable error code`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val sensitiveContent = "糖尿病 prompt test-key 13800000000 private@example.com Authorization Bearer raw-token"
        fakeLlmStatus.set(500)
        lateinit var error: AgentChatException

        val logs = captureAgentOperationLogs {
            error = assertThrows(AgentChatException::class.java) {
                chatService.sendMessage(
                    session.id,
                    "user-1",
                    SendMessageRequest(sensitiveContent, "log-failure-1")
                )
            }
        }

        val failureLogs = logs.filter { it.contains("operation=MODEL_COMPLETION") }
        assertEquals(1, failureLogs.size)
        val log = failureLogs.single()
        val turn = turnRepository.findAll().single { it.sessionId == session.id }
        assertTrue(log.contains("traceId=${error.traceId}"))
        assertTrue(log.contains("turnId=${turn.id}"))
        assertTrue(log.contains("terminalStatus=FAILED"))
        assertTrue(log.contains("errorCode=AI_PROVIDER_UNAVAILABLE"))
        assertTrue(Regex("""durationMs=\d+""").containsMatchIn(log))
        assertTrue(Regex("""sessionHash=[0-9a-f]{16}""").containsMatchIn(log))
        assertNoSensitiveLogData(log, sensitiveContent)
    }

    @Test
    fun `operation logger rejects sensitive metadata values`() {
        val sensitive = "Authorization-Bearer-token-private@example.com-13800000000"

        val logs = captureAgentOperationLogs {
            agentOperationLogger.completed("trace-1", "turn-1", "session-1", 1, sensitive)
            agentOperationLogger.failed("trace-2", "turn-2", "session-2", 2, sensitive)
        }
        val joined = logs.joinToString("\n")

        assertFalse(joined.contains("Authorization", ignoreCase = true))
        assertFalse(joined.contains("Bearer", ignoreCase = true))
        assertFalse(joined.contains("token", ignoreCase = true))
        assertFalse(joined.contains("private@example.com", ignoreCase = true))
        assertFalse(joined.contains("13800000000"))
        assertTrue(joined.contains("modelName=redacted"))
        assertTrue(joined.contains("errorCode=AGENT_INTERNAL_ERROR"))
    }

    private fun captureAgentOperationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(agentOperationLoggerName) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun assertHttpProviderFailure(
        idempotencyKey: String,
        expectedErrorCode: String,
        sensitiveValues: List<String>
    ) {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        lateinit var responseBody: String

        val logs = captureAgentOperationLogs {
            responseBody = mockMvc.perform(
                post("/api/chat/sessions/{id}/messages", session.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"content":"provider contract request","idempotencyKey":"$idempotencyKey"}"""
                    )
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value(expectedErrorCode))
                .andExpect(jsonPath("$.data.traceId").isNotEmpty)
                .andReturn()
                .response
                .contentAsString
        }

        val failed = turnRepository.findAll().single { it.sessionId == session.id }
        assertEquals(AgentTurnStatus.FAILED, failed.status)
        assertEquals(expectedErrorCode, failed.errorCode)

        val persistedMessages = messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id)
        assertEquals(listOf(1L), persistedMessages.map { it.sequenceNo })
        assertEquals(listOf("USER"), persistedMessages.map { it.role })
        assertEquals(emptyList<Long>(), chatService.getMessages(session.id, "user-1").map { it.sequenceNo })

        val failureLogs = logs.filter { it.contains("operation=MODEL_COMPLETION") }
        assertEquals(1, failureLogs.size)
        assertTrue(failureLogs.single().contains("terminalStatus=FAILED"))
        assertTrue(failureLogs.single().contains("errorCode=$expectedErrorCode"))

        val valuesThatMustBeRedacted = sensitiveValues + listOf(
            idempotencyKey,
            "test-key",
            "http://127.0.0.1:${fakeLlm.address.port}/v1"
        )
        assertRedacted(responseBody, valuesThatMustBeRedacted)
        assertRedacted(failureLogs.joinToString("\n"), valuesThatMustBeRedacted)
        assertRedacted(persistedMessages.joinToString("\n") { it.metadataJson }, valuesThatMustBeRedacted)
    }

    private fun assertRedacted(value: String, sensitiveValues: List<String>) {
        sensitiveValues.filter(String::isNotEmpty).forEach { sensitive ->
            assertFalse(value.contains(sensitive, ignoreCase = true), "Sensitive value was exposed: $sensitive")
        }
    }

    private fun assertNoSensitiveLogData(log: String, requestContent: String) {
        listOf(
            requestContent,
            "糖尿病",
            "prompt",
            "test-key",
            "13800000000",
            "private@example.com",
            "Authorization",
            "Bearer",
            "raw-token",
            "provider-secret-body",
            "http://127.0.0.1:${fakeLlm.address.port}/v1"
        ).forEach { sensitive -> assertFalse(log.contains(sensitive, ignoreCase = true)) }
    }

    private fun messageCount(sessionId: String) = messageRepository.countBySessionId(sessionId).toInt()

    private fun turnCount(sessionId: String) = turnRepository.countBySessionId(sessionId).toInt()

    companion object {
        private const val agentOperationLoggerName =
            "com.joysong.server.agent.diagnostics.AgentOperationLogger"
        private val fakeLlmCalls = AtomicInteger()
        private val fakeLlmStatus = AtomicInteger(200)
        private val fakeLlmDelayMs = AtomicLong()
        private val fakeLlmContent = AtomicReference("测试回复")
        private val fakeLlmRawResponse = AtomicReference<String?>(null)
        private val fakeIntentParserContent = AtomicReference(
            """{"intent":"CATALOG_QA","queryTarget":"DOCTOR","keywords":["context"]}"""
        )
        private val fakeLlmRequestBodies = CopyOnWriteArrayList<String>()
        private val fakeLlmExecutor = Executors.newCachedThreadPool()
        private val fakeLlm = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val requestBody = exchange.requestBody.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
                fakeLlmRequestBodies += requestBody
                fakeLlmCalls.incrementAndGet()
                Thread.sleep(fakeLlmDelayMs.get())
                val status = fakeLlmStatus.get()
                val streaming = Regex("\"stream\"\\s*:\\s*true").containsMatchIn(requestBody)
                val rawResponse = fakeLlmRawResponse.get()
                val intentParserRequest = Regex("\"model\"\\s*:\\s*\"intent-test-model\"")
                    .containsMatchIn(requestBody)
                val response = if (rawResponse != null) {
                    rawResponse.toByteArray(StandardCharsets.UTF_8)
                } else if (status != 200) {
                    """{"error":{"message":"provider-secret-body test-key 13800000000 private@example.com"}}""".toByteArray(StandardCharsets.UTF_8)
                } else if (streaming) {
                    """
                        data: {"id":"chatcmpl-test","choices":[{"delta":{"content":"测试回复"},"finish_reason":null}]}

                        data: [DONE]

                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                } else """
                    {
                      "id": "chatcmpl-test",
                      "choices": [{"message": {"content": ${ObjectMapper().writeValueAsString(if (intentParserRequest) fakeIntentParserContent.get() else fakeLlmContent.get())}}, "finish_reason": "stop"}],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 2, "total_tokens": 12}
                    }
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add(
                    "Content-Type",
                    if (streaming) "text/event-stream; charset=utf-8" else "application/json; charset=utf-8"
                )
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            executor = fakeLlmExecutor
            start()
        }

        @Container
        @JvmField
        val mysql = ReportingAgentChatMySqlContainer("mysql:8.0.39")
            .withDatabaseName(worktreeDatabaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @JvmStatic
        @DynamicPropertySource
        fun configureApplication(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("jwt.secret") { "test-jwt-secret-must-be-at-least-32-bytes-long" }
            registry.add("google.client-id") { "test-google-client-id" }
            registry.add("admin.bootstrap.phone") { "13800000000" }
            registry.add("admin.bootstrap.password") { "test-admin-password" }
            registry.add("payment.stripe.secret-key") { "sk_test_agent_chat" }
            registry.add("payment.stripe.webhook-secret") { "whsec_agent_chat" }
            registry.add("ai-agent.enabled") { "true" }
            registry.add("ai-agent.base-url") { "http://127.0.0.1:${fakeLlm.address.port}/v1" }
            registry.add("ai-agent.api-key") { "test-key" }
            registry.add("ai-agent.model") { "test-model" }
            registry.add("ai-agent.intent-parser-enabled") { "true" }
            registry.add("ai-agent.intent-model") { "intent-test-model" }
            registry.add("ai-agent.demo-fallback-enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun stopFakeLlm() {
            fakeLlm.stop(0)
            fakeLlmExecutor.shutdownNow()
        }

        private fun worktreeDatabaseName(): String {
            val worktreeName = Paths.get(System.getProperty("user.dir")).parent.fileName.toString()
            return "myapp_worktree_${worktreeName.replace(Regex("[^A-Za-z0-9]+"), "_")}".lowercase()
        }
    }
}

class ReportingAgentChatMySqlContainer(imageName: String) :
    MySQLContainer<ReportingAgentChatMySqlContainer>(imageName) {

    override fun start() {
        println("AGENT_CHAT_TEST_DB_HOST=$host")
        println("AGENT_CHAT_TEST_DB_NAME=$databaseName")
        require(databaseName.startsWith("myapp_worktree_")) {
            "Refusing to start integration test with non-worktree database: $databaseName"
        }
        super.start()
    }
}
