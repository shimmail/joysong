package com.joysong.server.agent

import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatService
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestTemplate
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

@Tag("mysql-integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.task.scheduling.enabled=false"]
)
class AgentChatFlowIntegrationTest {

    @Autowired
    private lateinit var chatService: ChatService

    @Autowired
    private lateinit var messageRepository: ChatMessageRepository

    @Autowired
    private lateinit var turnRepository: AgentTurnRepository

    @Autowired
    private lateinit var sessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var turnLifecycleService: TurnLifecycleService

    @Autowired
    @Qualifier("llmRestTemplate")
    private lateinit var llmRestTemplate: RestTemplate

    private val transactionStates = CopyOnWriteArrayList<Boolean>()
    private lateinit var transactionInterceptor: ClientHttpRequestInterceptor

    @BeforeEach
    fun installTransactionObserver() {
        fakeLlmCalls.set(0)
        fakeLlmStatus.set(200)
        transactionStates.clear()
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
        assertEquals("IDEMPOTENCY_REPLAY_EXPIRED", expired.code)
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
    fun `legacy streaming ignores idempotency replay and keeps each delta call in its original transaction`() {
        val session = chatService.createSession("user-1", CreateSessionRequest(persona = "CONSULTANT"))
        val firstDeltas = mutableListOf<String>()
        val secondDeltas = mutableListOf<String>()
        ReflectionTestUtils.setField(chatService, "streamEnabled", true)

        try {
            val first = chatService.sendMessageStreaming(
                session.id,
                "user-1",
                SendMessageRequest("stream once", "same-stream-key"),
                firstDeltas::add
            )
            val second = chatService.sendMessageStreaming(
                session.id,
                "user-1",
                SendMessageRequest("stream once", "same-stream-key"),
                secondDeltas::add
            )

            assertEquals(listOf("测试回复"), firstDeltas)
            assertEquals(listOf("测试回复"), secondDeltas)
            assertFalse(first.message.id == second.message.id)
            assertEquals(2, fakeLlmCalls.get())
            assertEquals(0, turnCount(session.id))
            assertEquals(
                listOf(1L, 2L, 3L, 4L),
                messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id).map { it.sequenceNo }
            )
            assertEquals(listOf(true, true), transactionStates)
        } finally {
            ReflectionTestUtils.setField(chatService, "streamEnabled", false)
        }
    }

    private fun messageCount(sessionId: String) = messageRepository.countBySessionId(sessionId).toInt()

    private fun turnCount(sessionId: String) = turnRepository.countBySessionId(sessionId).toInt()

    companion object {
        private val fakeLlmCalls = AtomicInteger()
        private val fakeLlmStatus = AtomicInteger(200)
        private val fakeLlmExecutor = Executors.newSingleThreadExecutor()
        private val fakeLlm = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val requestBody = exchange.requestBody.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
                fakeLlmCalls.incrementAndGet()
                val status = fakeLlmStatus.get()
                val streaming = Regex("\"stream\"\\s*:\\s*true").containsMatchIn(requestBody)
                val response = if (status != 200) {
                    """{"error":{"message":"test failure"}}""".toByteArray(StandardCharsets.UTF_8)
                } else if (streaming) {
                    """
                        data: {"id":"chatcmpl-test","choices":[{"delta":{"content":"测试回复"},"finish_reason":null}]}

                        data: [DONE]

                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                } else """
                    {
                      "id": "chatcmpl-test",
                      "choices": [{"message": {"content": "测试回复"}, "finish_reason": "stop"}],
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
            registry.add("openai.base-url") { "http://127.0.0.1:${fakeLlm.address.port}/v1" }
            registry.add("openai.api-key") { "test-key" }
            registry.add("openai.model") { "test-model" }
            registry.add("openai.stream-enabled") { "false" }
            registry.add("openai.intent-parser-enabled") { "false" }
            registry.add("openai.demo-fallback-enabled") { "false" }
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
        super.start()
        println("AGENT_CHAT_TEST_DB_HOST=$host")
        println("AGENT_CHAT_TEST_DB_NAME=$databaseName")
    }
}
