package com.joysong.server.agent

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.diagnostics.AgentOperationLogger
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.ComparisonOperand
import com.joysong.server.agent.service.ComparisonRequest
import com.joysong.server.agent.streaming.AgentStreamEvent
import com.joysong.server.agent.streaming.AgentStreamSink
import com.joysong.server.agent.streaming.AgentStreamingService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
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
import org.springframework.http.client.support.HttpRequestWrapper
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestTemplate
import org.slf4j.LoggerFactory
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    private lateinit var institutionRepository: InstitutionRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var turnLifecycleService: TurnLifecycleService

    @Autowired
    private lateinit var agentStreamingService: AgentStreamingService

    @Autowired
    private lateinit var aiAgentProperties: AiAgentProperties

    @Autowired
    @Qualifier("agentLlmRestTemplate")
    private lateinit var llmRestTemplate: RestTemplate

    @Autowired
    @Qualifier("agentIntentParserRestTemplate")
    private lateinit var intentParserRestTemplate: RestTemplate

    private val transactionStates = CopyOnWriteArrayList<Boolean>()
    private lateinit var transactionInterceptor: ClientHttpRequestInterceptor
    private lateinit var fakeProviderInterceptor: ClientHttpRequestInterceptor

    @BeforeEach
    fun installTransactionObserver() {
        userRepository.saveAllAndFlush(
            listOf(
                UserEntity(id = "user-1", passwordHash = "test-password-hash", nickname = "测试用户一"),
                UserEntity(id = "user-2", passwordHash = "test-password-hash", nickname = "测试用户二")
            )
        )
        fakeLlmCalls.set(0)
        fakeLlmStatus.set(200)
        fakeLlmDelayMs.set(0)
        fakeLlmContent.set("测试回复")
        fakeLlmRawResponse.set(null)
        fakeLlmStreamResponse.set(defaultStreamResponse)
        fakeStreamEntered.set(null)
        fakeStreamRelease.set(null)
        fakeIntentParserContent.set("""{"intent":"CATALOG_QA","queryTarget":"DOCTOR","keywords":["context"]}""")
        fakeLlmRequestBodies.clear()
        aiAgentProperties.intentModel = "intent-test-model"
        transactionStates.clear()
        llmRestTemplate.requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(1_000)
            setReadTimeout(100)
        }
        intentParserRestTemplate.requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(1_000)
            setReadTimeout(100)
        }
        fakeProviderInterceptor = ClientHttpRequestInterceptor { request, body, execution ->
            val localRequest = object : HttpRequestWrapper(request) {
                override fun getURI() = java.net.URI(
                    "http",
                    null,
                    "127.0.0.1",
                    fakeLlm.address.port,
                    "/v1/chat/completions",
                    null,
                    null
                )
            }
            execution.execute(localRequest, body)
        }
        transactionInterceptor = ClientHttpRequestInterceptor { request, body, execution ->
            transactionStates += TransactionSynchronizationManager.isActualTransactionActive()
            execution.execute(request, body)
        }
        llmRestTemplate.interceptors = llmRestTemplate.interceptors + listOf(
            fakeProviderInterceptor,
            transactionInterceptor
        )
        intentParserRestTemplate.interceptors = intentParserRestTemplate.interceptors + fakeProviderInterceptor
    }

    @AfterEach
    fun removeTransactionObserver() {
        llmRestTemplate.interceptors = llmRestTemplate.interceptors.filterNot {
            it === transactionInterceptor || it === fakeProviderInterceptor
        }
        intentParserRestTemplate.interceptors = intentParserRestTemplate.interceptors.filterNot {
            it === fakeProviderInterceptor
        }
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
        assertEquals(2, fakeLlmCalls.get())
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
    @WithMockUser(username = "user-1")
    fun `history restores supported catalog cards on their assistant message`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val started = turnLifecycleService.beginTurn(
            session.id,
            "user-1",
            "show catalog cards",
            "history-cards-1"
        ) as BeginTurnResult.Started
        val turn = turnRepository.findById(started.turnId).orElseThrow().apply {
            status = AgentTurnStatus.SUCCEEDED
            completedAt = LocalDateTime.now()
        }
        turnRepository.saveAndFlush(turn)
        messageRepository.saveAndFlush(
            ChatMessageEntity(
                sessionId = session.id,
                turnId = turn.id,
                sequenceNo = started.sequenceNo * 2,
                role = "ASSISTANT",
                content = "catalog reply",
                metadataJson = """{
                    "catalogItems":[
                      {"type":"DOCTOR","id":"doctor-1","name":"Doctor","subtitle":"","summary":"","attributes":{}},
                      {"type":"INSTITUTION","id":"institution-1","name":"Institution","subtitle":"","summary":"","attributes":{}},
                      {"type":"PROJECT","id":"project-1","name":"Project","subtitle":"","summary":"","attributes":{}},
                      {"type":"INSTITUTION_PROJECT","id":"link-1","name":"Institution project","subtitle":"","summary":"","attributes":{},"institutionId":"institution-1","projectId":"project-1"},
                      {"type":"UNKNOWN","id":"unknown-1","name":"Unknown","subtitle":"","summary":"","attributes":{}}
                    ]
                }""".trimIndent()
            )
        )

        mockMvc.perform(get("/api/chat/sessions/{id}/messages", session.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].catalogItems").isEmpty)
            .andExpect(jsonPath("$.data[1].catalogItems.length()").value(4))
            .andExpect(jsonPath("$.data[1].catalogItems[0].type").value("DOCTOR"))
            .andExpect(jsonPath("$.data[1].catalogItems[3].type").value("INSTITUTION_PROJECT"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `history restores comparison request and report on the producing assistant message only`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        completeComparisonTurn(session.id, "compare Alpha and Beta", "history-comparison-1")
        val ordinary = turnLifecycleService.beginTurn(
            session.id,
            "user-1",
            "ordinary question",
            "history-ordinary-1"
        ) as BeginTurnResult.Started
        turnLifecycleService.completeTurn(
            CompleteTurnCommand(
                turnId = ordinary.turnId,
                content = "ordinary answer",
                intent = "GENERAL_CHAT",
                queryTarget = null,
                nextAction = "NONE"
            )
        )

        val response = mockMvc.perform(get("/api/chat/sessions/{id}/messages", session.id))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val messages = objectMapper.readTree(response).path("data")

        assertEquals(4, messages.size())
        assertTrue(messages[0].path("comparisonRequest").isNull)
        assertTrue(messages[0].path("catalogReport").isNull)
        assertEquals("project-alpha", messages[1].path("comparisonRequest").path("operands")[0].path("entityId").asText())
        assertEquals("Project comparison", messages[1].path("catalogReport").path("title").asText())
        assertTrue(messages[2].path("comparisonRequest").isNull)
        assertTrue(messages[2].path("catalogReport").isNull)
        assertTrue(messages[3].path("comparisonRequest").isNull)
        assertTrue(messages[3].path("catalogReport").isNull)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `history independently degrades malformed optional comparison metadata`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        completeComparisonTurn(session.id, "first comparison", "history-malformed-request-1")
        completeComparisonTurn(session.id, "second comparison", "history-malformed-report-1")
        val assistants = messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id)
            .filter { it.role == "ASSISTANT" }
        assistants[0].metadataJson = objectMapper.writeValueAsString(
            mapOf(
                "intent" to "COMPARISON",
                "comparisonRequest" to "not-an-object",
                "catalogReport" to comparisonReport()
            )
        )
        assistants[1].metadataJson = objectMapper.writeValueAsString(
            mapOf(
                "intent" to "COMPARISON",
                "comparisonRequest" to comparisonRequest(),
                "catalogReport" to "not-an-object"
            )
        )
        messageRepository.saveAllAndFlush(assistants)

        val response = mockMvc.perform(get("/api/chat/sessions/{id}/messages", session.id))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val messages = objectMapper.readTree(response).path("data")

        assertTrue(messages[1].path("comparisonRequest").isNull)
        assertEquals("Project comparison", messages[1].path("catalogReport").path("title").asText())
        assertEquals("project-alpha", messages[3].path("comparisonRequest").path("operands")[0].path("entityId").asText())
        assertTrue(messages[3].path("catalogReport").isNull)
    }

    @Test
    fun `ambiguous request uses intent model and does not override its current institution target from history`() {
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
        assertEquals(2, fakeLlmCalls.get())
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
    fun `HTTP synchronous completion binds comparison request and report to assistant message`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        completeComparisonTurn(session.id, "compare Alpha and Beta", "http-comparison-1")

        val result = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"compare Alpha and Beta","idempotencyKey":"http-comparison-1"}""")
        )
            .andExpect(status().isOk)
            .andReturn()
        val data = objectMapper.readTree(result.response.contentAsString).path("data")

        assertEquals("project-alpha", data.path("message").path("comparisonRequest").path("operands")[0].path("entityId").asText())
        assertEquals(data.path("catalogReport"), data.path("message").path("catalogReport"))
        assertEquals("Project comparison", data.path("message").path("catalogReport").path("title").asText())
        assertFalse(data.has("comparisonRequest"))
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

        val logs = assertHttpProviderFailure(
            idempotencyKey = "http-provider-status-$providerStatus",
            expectedErrorCode = "AI_PROVIDER_UNAVAILABLE",
            sensitiveValues = listOf("provider-secret-body", "private@example.com", "13800000000")
        )
        val providerLog = logs.single {
            it.contains("operation=PROVIDER_CALL") &&
                it.contains("providerPhase=MODEL_COMPLETION") &&
                it.contains("modelName=redacted")
        }
        val expectedCategory = when (providerStatus) {
            401 -> "AUTH"
            429 -> "RATE_LIMIT"
            else -> "UPSTREAM_5XX"
        }
        assertTrue(providerLog.contains("httpStatus=$providerStatus"))
        assertTrue(providerLog.contains("providerCategory=$expectedCategory"))
        assertNoSensitiveLogData(providerLog, "provider contract request")
        if (providerStatus == 429) {
            assertTrue(providerLog.contains("providerHost=www.fastaitoken.com"))
            assertFalse(providerLog.contains("test-model"))
            assertTrue(providerLog.contains("providerErrorCode=rate_limit_exceeded"))
            assertTrue(Regex("""messageCount=\d+""").containsMatchIn(providerLog))
            assertTrue(Regex("""systemMessageCount=\d+""").containsMatchIn(providerLog))
            assertTrue(Regex("""totalCharacterCount=\d+""").containsMatchIn(providerLog))
        }
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

        val logs = assertHttpProviderFailure(
            idempotencyKey = "http-timeout-1",
            expectedErrorCode = "AI_PROVIDER_TIMEOUT",
            sensitiveValues = listOf("provider-secret-body")
        )
        val providerLog = logs.single {
            it.contains("operation=PROVIDER_CALL") && it.contains("providerPhase=MODEL_COMPLETION")
        }
        assertTrue(providerLog.contains("httpStatus=none"))
        assertTrue(providerLog.contains("providerCategory=READ_TIMEOUT"))
        assertNoSensitiveLogData(providerLog, "provider contract request")
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
    fun `HTTP streaming success emits ordered events and persists one complete turn`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "你好", "http-stream-success-1")

        assertEventOrder(response, "event:started", "event:delta", "event:completed")
        assertFalse(response.contains("event:failed"))
        assertEquals(1, streamingProviderCallCount())
        assertEquals(listOf("USER", "ASSISTANT"), messages(session.id).map { it.role })
        assertEquals("测试回复", messages(session.id).last().content)
        assertEquals(AgentTurnStatus.SUCCEEDED, turn(session.id).status)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP human consultation returns and restores a fixed institution handoff without model calls`() {
        val seed = seedConsultableInstitution()
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val current = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"我想转人工咨询","idempotencyKey":"http-human-handoff-1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.intent").value("HUMAN_CONSULTATION"))
            .andExpect(jsonPath("$.data.queryTarget").value("INSTITUTION"))
            .andExpect(jsonPath("$.data.nextAction").value("SELECT_INSTITUTION"))
            .andExpect(jsonPath("$.data.catalogItems[0].type").value("INSTITUTION"))
            .andExpect(jsonPath("$.data.catalogItems[0].institutionId").value(seed.institution.id))
            .andExpect(jsonPath("$.data.catalogItems[0].canChatWithHuman").value(true))
            .andReturn().response.contentAsString
        assertFalse(current.contains(seed.consultantId))
        assertEquals(0, fakeLlmCalls.get())

        val history = mockMvc.perform(get("/api/chat/sessions/{id}/messages", session.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[1].catalogItems[0].type").value("INSTITUTION"))
            .andExpect(jsonPath("$.data[1].catalogItems[0].institutionId").value(seed.institution.id))
            .andExpect(jsonPath("$.data[1].catalogItems[0].canChatWithHuman").value(true))
            .andReturn().response.contentAsString
        assertFalse(history.contains(seed.consultantId))
        val assistantMetadata = messages(session.id).single { it.role == "ASSISTANT" }.metadataJson
        assertFalse(assistantMetadata.contains(seed.consultantId))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP human consultation reports a soft deleted named institution as unavailable`() {
        jdbcTemplate.update("UPDATE institution_memberships SET status = 'PENDING' WHERE status = 'APPROVED'")
        val alternative = seedConsultableInstitution(
            name = "可咨询机构 ${UUID.randomUUID()}",
            rating = "5.0"
        )
        val deletedId = UUID.randomUUID().toString()
        institutionRepository.saveAndFlush(
            InstitutionEntity(
                id = deletedId,
                name = "星颜",
                city = "上海",
                isVerified = true
            )
        )
        institutionRepository.deleteById(deletedId)
        institutionRepository.flush()

        assertFalse(institutionRepository.findAll().any { it.id == deletedId })
        assertEquals(
            1L,
            institutionRepository.countSoftDeletedNamesMentionedInQuery("我想联系星颜做真人咨询")
        )
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val responseBody = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"我想联系星颜做真人咨询","idempotencyKey":"http-soft-deleted-handoff-1"}""")
        )
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.data.message.content").value(
                    "你提到的机构目前无法提供真人转接。你可以选择下方其他机构，查看其当前可联系的咨询师。"
                )
            )
            .andExpect(jsonPath("$.data.catalogItems.length()").value(1))
            .andExpect(jsonPath("$.data.catalogItems[0].institutionId").value(alternative.institution.id))
            .andReturn().response.contentAsString
        assertFalse(responseBody.contains(deletedId))
        assertEquals(0, fakeLlmCalls.get())
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP human consultation stream emits started then completed without delta`() {
        seedConsultableInstitution()
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "我想转人工咨询", "http-human-handoff-stream-1")

        assertEventOrder(response, "event:started", "event:completed")
        assertFalse(response.contains("event:delta"))
        assertEquals(0, streamingProviderCallCount())
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP planning stream suppresses unsafe deltas and persists only policy safe content`() {
        fakeLlmStreamResponse.set(streamResponse("结合你低痛偏好，建议选择A"))
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "帮我规划适合自己的项目", "http-stream-planning-1")

        assertEventOrder(response, "event:started", "event:completed")
        assertFalse(response.contains("event:delta"))
        val assistant = messages(session.id).single { it.role == "ASSISTANT" }
        assertTrue(assistant.content.contains("信息参考"))
        assertFalse(assistant.content.contains("低痛偏好"))
        assertFalse(assistant.content.contains("建议选择A"))
        assertFalse(response.contains("低痛偏好"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP streaming replay emits stored completion without another provider call or message`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val first = stream(session.id, "你好", "http-stream-replay-1")
        val callsAfterFirst = streamingProviderCallCount()

        val replay = stream(session.id, "你好", "http-stream-replay-1")

        assertTrue(first.contains("event:started"))
        assertFalse(replay.contains("event:started"))
        assertFalse(replay.contains("event:delta"))
        assertTrue(replay.contains("event:completed"))
        assertEquals(callsAfterFirst, streamingProviderCallCount())
        assertEquals(2, messageCount(session.id))
        assertEquals(1, turnCount(session.id))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP streaming duplicate running turn returns conflict before provider call`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val started = turnLifecycleService.beginTurn(session.id, "user-1", "你好", "http-stream-running-1") as BeginTurnResult.Started
        val callsBefore = streamingProviderCallCount()
        try {
            mockMvc.perform(
                post("/api/chat/sessions/{id}/messages/stream", session.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"你好","idempotencyKey":"http-stream-running-1"}""")
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.message").value("TURN_IN_PROGRESS"))
            assertEquals(callsBefore, streamingProviderCallCount())
            assertEquals(1, messageCount(session.id))
        } finally {
            turnLifecycleService.cancelTurn(started.turnId, "TEST_CLEANUP", 0)
        }
    }

    @ParameterizedTest(name = "streaming provider HTTP {0} is atomic")
    @ValueSource(ints = [400, 429, 500])
    @WithMockUser(username = "user-1")
    fun `HTTP streaming upstream errors fail atomically with redacted terminal event`(providerStatus: Int) {
        fakeLlmStatus.set(providerStatus)
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "你好", "http-stream-upstream-$providerStatus")

        assertEventOrder(response, "event:started", "event:failed")
        assertTrue(response.contains("AI_PROVIDER_UNAVAILABLE"))
        assertFalse(response.contains("provider-secret-body"))
        assertEquals(listOf("USER"), messages(session.id).map { it.role })
        assertEquals(AgentTurnStatus.FAILED, turn(session.id).status)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP streaming upstream body is absent from real logback events`() {
        fakeLlmStatus.set(500)
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val logs = captureAgentOperationLogs {
            val response = stream(session.id, "redaction request", "http-stream-log-redaction-1")
            assertTrue(response.contains("event:failed"))
        }.joinToString("\n")

        listOf("provider-secret-body", "test-key", "private@example.com", "13800000000").forEach {
            assertFalse(logs.contains(it, ignoreCase = true), "Sensitive provider data leaked into Logback: $it")
        }
        assertTrue(logs.contains("operation=PROVIDER_CALL"))
        assertTrue(logs.contains("operation=MODEL_COMPLETION"))
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP streaming timeout fails atomically and remains retryable`() {
        fakeLlmDelayMs.set(500)
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "你好", "http-stream-timeout-1")

        assertTrue(response.contains("event:failed"))
        assertTrue(response.contains("AI_PROVIDER_TIMEOUT"))
        assertTrue(response.contains("\"retryable\":true"))
        assertEquals(listOf("USER"), messages(session.id).map { it.role })
        assertEquals(AgentTurnStatus.FAILED, turn(session.id).status)
    }

    @ParameterizedTest(name = "stream payload {index} fails atomically")
    @ValueSource(strings = ["data: {not-json}\n\ndata: [DONE]\n\n", "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"])
    @WithMockUser(username = "user-1")
    fun `HTTP malformed or incomplete provider stream never persists a partial assistant`(payload: String) {
        fakeLlmStreamResponse.set(payload)
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))

        val response = stream(session.id, "你好", "http-stream-bad-${payload.hashCode()}")

        assertTrue(response.contains("event:failed"))
        assertFalse(response.contains("event:completed"))
        assertEquals(listOf("USER"), messages(session.id).map { it.role })
        assertEquals(AgentTurnStatus.FAILED, turn(session.id).status)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `HTTP async completion callback fails the running turn without persisting assistant content`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fakeStreamEntered.set(entered)
        fakeStreamRelease.set(release)
        val initial = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"你好","idempotencyKey":"stream-disconnect-1"}""")
        ).andExpect(request().asyncStarted()).andReturn()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        requireNotNull(initial.request.asyncContext).complete()
        release.countDown()
        awaitTurnStatus(session.id, AgentTurnStatus.FAILED)

        assertEquals(listOf("USER"), messages(session.id).map { it.role })
        assertEquals("CLIENT_DISCONNECTED", turn(session.id).errorCode)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `failed streaming execution retries the same idempotency key without duplicating user message`() {
        fakeLlmStatus.set(500)
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val failed = stream(session.id, "你好", "http-stream-retry-1")
        assertTrue(failed.contains("event:failed"))
        val firstTurn = turn(session.id)
        val firstUser = messages(session.id).single()
        val firstTurnId = firstTurn.id
        val firstTraceId = firstTurn.traceId
        val firstSequenceNo = firstTurn.sequenceNo
        val firstStartedAt = firstTurn.startedAt
        val firstLeaseExpiresAt = firstTurn.leaseExpiresAt
        fakeLlmStatus.set(200)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fakeStreamEntered.set(entered)
        fakeStreamRelease.set(release)

        val retryInitial = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"你好","idempotencyKey":"http-stream-retry-1"}""")
        ).andExpect(request().asyncStarted()).andReturn()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val runningRetry = turn(session.id)
        assertEquals(firstTurnId, runningRetry.id)
        assertEquals(firstTraceId, runningRetry.traceId)
        assertEquals(firstSequenceNo, runningRetry.sequenceNo)
        assertEquals(AgentTurnStatus.RUNNING, runningRetry.status)
        assertEquals(null, runningRetry.errorCode)
        assertEquals(null, runningRetry.completedAt)
        assertTrue(runningRetry.startedAt.isAfter(firstStartedAt))
        assertTrue(requireNotNull(runningRetry.leaseExpiresAt).isAfter(requireNotNull(firstLeaseExpiresAt)))
        assertEquals(firstUser.id, messages(session.id).single().id)
        release.countDown()
        val retried = mockMvc.perform(asyncDispatch(retryInitial)).andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(retried.contains("event:completed"))
        assertEquals(2, streamingProviderCallCount())
        assertEquals(listOf("USER", "ASSISTANT"), messages(session.id).map { it.role })
        assertEquals(1, turnCount(session.id))
        assertEquals(AgentTurnStatus.SUCCEEDED, turn(session.id).status)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `concurrent duplicate stream keeps one running turn user message and provider call`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fakeStreamEntered.set(entered)
        fakeStreamRelease.set(release)

        val first = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"你好","idempotencyKey":"http-stream-concurrent-1"}""")
        ).andExpect(request().asyncStarted()).andReturn()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", session.id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"你好","idempotencyKey":"http-stream-concurrent-1"}""")
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("TURN_IN_PROGRESS"))
        assertEquals(1, turnCount(session.id))
        assertEquals(listOf("USER"), messages(session.id).map { it.role })
        assertEquals(1, streamingProviderCallCount())
        release.countDown()
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk)
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
        assertTrue(log.contains("modelName=redacted"))
        assertFalse(log.contains("test-model"))
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
            agentOperationLogger.providerFailed(
                traceId = "trace-3",
                turnId = "turn-3",
                providerPhase = sensitive,
                providerHost = sensitive,
                modelName = sensitive,
                httpStatus = 999,
                providerCategory = sensitive,
                providerErrorCode = sensitive,
                exceptionType = sensitive,
                durationMs = -1,
                messageCount = -1,
                systemMessageCount = -1,
                totalCharacterCount = -1
            )
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

    private fun stream(sessionId: String, content: String, idempotencyKey: String): String {
        val initial = mockMvc.perform(
            post("/api/chat/sessions/{id}/messages/stream", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SendMessageRequest(content, idempotencyKey)))
        )
            .andExpect(request().asyncStarted())
            .andReturn()
        return mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
    }

    private fun assertEventOrder(response: String, vararg events: String) {
        val positions = events.map { event ->
            response.indexOf(event).also { assertTrue(it >= 0, "Missing $event in $response") }
        }
        assertEquals(positions.sorted(), positions)
    }

    private fun messages(sessionId: String) = messageRepository.findBySessionIdOrderBySequenceNoAsc(sessionId)

    private fun turn(sessionId: String) = turnRepository.findAll().single { it.sessionId == sessionId }

    private fun streamingProviderCallCount() = fakeLlmRequestBodies.count {
        Regex("\"stream\"\\s*:\\s*true").containsMatchIn(it)
    }

    private fun awaitTurnStatus(sessionId: String, expected: AgentTurnStatus) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (turnRepository.findAll().singleOrNull { it.sessionId == sessionId }?.status == expected) return
            Thread.yield()
        }
        assertEquals(expected, turn(sessionId).status)
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
    ): List<String> {
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
        return logs
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

    private fun completeComparisonTurn(sessionId: String, content: String, idempotencyKey: String) {
        val started = turnLifecycleService.beginTurn(sessionId, "user-1", content, idempotencyKey) as BeginTurnResult.Started
        turnLifecycleService.completeTurn(
            CompleteTurnCommand(
                turnId = started.turnId,
                content = "comparison answer",
                intent = "COMPARISON",
                queryTarget = "PROJECT",
                nextAction = "SHOW_COMPARISON",
                catalogReport = comparisonReport(),
                comparisonRequest = comparisonRequest()
            )
        )
    }

    private data class ConsultableInstitutionSeed(
        val institution: InstitutionEntity,
        val consultantId: String
    )

    private fun seedConsultableInstitution(
        name: String = "Human Handoff Clinic ${UUID.randomUUID()}",
        rating: String = "0"
    ): ConsultableInstitutionSeed {
        val institution = institutionRepository.saveAndFlush(
            InstitutionEntity(
                id = UUID.randomUUID().toString(),
                name = name,
                city = "Shanghai",
                rating = rating.toBigDecimal(),
                isVerified = true
            )
        )
        val consultantId = UUID.randomUUID().toString()
        userRepository.saveAndFlush(
            UserEntity(
                id = consultantId,
                passwordHash = "test-password-hash",
                nickname = "Consultant ${UUID.randomUUID()}"
            )
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_at)
            VALUES (?, ?, ?, 'CONSULTANT', 'APPROVED', CURRENT_TIMESTAMP)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            consultantId,
            institution.id
        )
        return ConsultableInstitutionSeed(institution, consultantId)
    }

    private fun comparisonRequest() = ComparisonRequest(
        operands = listOf(
            ComparisonOperand(AgentQueryTarget.PROJECT, "project-alpha", "Alpha"),
            ComparisonOperand(AgentQueryTarget.PROJECT, "project-beta", "Beta")
        ),
        targetType = AgentQueryTarget.PROJECT,
        dimensions = listOf("PRICE")
    )

    private fun comparisonReport(): AgentCatalogReportResponse {
        val item = AgentCatalogItemResponse(
            type = "PROJECT",
            id = "project-alpha",
            name = "Alpha",
            subtitle = "",
            summary = "",
            attributes = linkedMapOf("Reference price" to "1000")
        )
        return AgentCatalogReportResponse(
            mode = "COMPARISON",
            title = "Project comparison",
            summary = "Structured comparison",
            items = listOf(item),
            comparisonDimensions = listOf("Reference price"),
            warnings = emptyList()
        )
    }

    companion object {
        private const val agentOperationLoggerName =
            "com.joysong.server.agent.diagnostics.AgentOperationLogger"
        private val fakeLlmCalls = AtomicInteger()
        private val fakeLlmStatus = AtomicInteger(200)
        private val fakeLlmDelayMs = AtomicLong()
        private val fakeLlmContent = AtomicReference("测试回复")
        private val fakeLlmRawResponse = AtomicReference<String?>(null)
        private const val defaultStreamResponse =
            "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{\"content\":\"测试回复\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
        private val fakeLlmStreamResponse = AtomicReference(defaultStreamResponse)
        private val fakeStreamEntered = AtomicReference<CountDownLatch?>(null)
        private val fakeStreamRelease = AtomicReference<CountDownLatch?>(null)
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
                if (streaming) {
                    fakeStreamEntered.get()?.countDown()
                    fakeStreamRelease.get()?.await(5, TimeUnit.SECONDS)
                }
                val rawResponse = fakeLlmRawResponse.get()
                val intentParserRequest = Regex("\"model\"\\s*:\\s*\"intent-test-model\"")
                    .containsMatchIn(requestBody)
                val response = if (rawResponse != null) {
                    rawResponse.toByteArray(StandardCharsets.UTF_8)
                } else if (status != 200) {
                    """{"error":{"code":"rate_limit_exceeded","message":"provider-secret-body test-key 13800000000 private@example.com"}}""".toByteArray(StandardCharsets.UTF_8)
                } else if (streaming) {
                    fakeLlmStreamResponse.get().toByteArray(StandardCharsets.UTF_8)
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
            registry.add("ai-agent.provider") { "QWEN" }
            registry.add("ai-agent.base-url") { "https://dashscope.aliyuncs.com/compatible-mode/v1" }
            registry.add("ai-agent.api-key") { "test-key" }
            registry.add("ai-agent.model") { "test-model" }
            registry.add("ai-agent.intent-model") { "intent-test-model" }
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

        private fun streamResponse(content: String): String =
            "data: {\"id\":\"chatcmpl-test\",\"choices\":[{\"delta\":{\"content\":${ObjectMapper().writeValueAsString(content)}},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n"
    }
}

class ReportingAgentChatMySqlContainer(imageName: String) :
    MySQLContainer<ReportingAgentChatMySqlContainer>(imageName) {

    override fun start() {
        println("AGENT_CHAT_TEST_DB_HOST=$host")
        println("AGENT_CHAT_TEST_DB_NAME=$databaseName")
        require(databaseName.startsWith("myapp_worktree_")) {
            "Refusing to start integration test with unexpected database: $databaseName"
        }
        super.start()
    }
}
