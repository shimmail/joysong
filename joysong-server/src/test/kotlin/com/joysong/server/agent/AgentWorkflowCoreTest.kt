package com.joysong.server.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.context.AgentSessionSummary
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.IdempotencyKeyConflictException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

class AgentWorkflowCoreTest {
    private val sessions = mockk<ChatSessionRepository>()
    private val messages = mockk<ChatMessageRepository>()
    private val turns = mockk<AgentTurnRepository>()
    private val objectMapper = ObjectMapper()
    private val context = AgentContextBuilder(sessions, messages, turns, objectMapper, 20, 7)
    private val lifecycle = TurnLifecycleService(sessions, messages, turns, context, objectMapper)

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
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", savedTurn.captured.requestHash)
        assertEquals(1, savedMessage.captured.sequenceNo)
        assertEquals("USER", savedMessage.captured.role)
        assertEquals("hello", savedMessage.captured.content)
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
    fun `rejects an idempotency key reused with a different request`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns turn(requestHash = "not-the-hash")

        assertThrows(IdempotencyKeyConflictException::class.java) {
            lifecycle.beginTurn("session-1", "user-1", "hello", "key-1")
        }
    }

    @Test
    fun `returns in progress when the session already has a running turn`() {
        every { sessions.findByIdAndUserIdForUpdate("session-1", "user-1") } returns session()
        every { turns.findBySessionIdAndIdempotencyKey("session-1", "key-1") } returns null
        every { turns.findBySessionIdAndStatus("session-1", AgentTurnStatus.RUNNING) } returns turn(status = AgentTurnStatus.RUNNING)

        assertEquals(BeginTurnResult.InProgress, lifecycle.beginTurn("session-1", "user-1", "hello", "key-1"))
    }

    @Test
    fun `marks successful turn complete once with replay metadata`() {
        val running = turn(status = AgentTurnStatus.RUNNING)
        val assistant = slot<ChatMessageEntity>()
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
    }

    @Test
    fun `does not duplicate assistant message when completion is repeated`() {
        val succeeded = turn(status = AgentTurnStatus.SUCCEEDED)
        val assistant = ChatMessageEntity(sessionId = "session-1", turnId = succeeded.id, sequenceNo = 2, role = "ASSISTANT", content = "final")
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
        val failTransaction = TurnLifecycleService::class.java.declaredMethods.first { it.name == "failTurn" }
            .getAnnotation(Transactional::class.java)
        val cancelTransaction = TurnLifecycleService::class.java.declaredMethods.first { it.name == "cancelTurn" }
            .getAnnotation(Transactional::class.java)
        assertEquals(Propagation.REQUIRES_NEW, failTransaction.propagation)
        assertEquals(Propagation.REQUIRES_NEW, cancelTransaction.propagation)
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
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-1") } returns session
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(
            message(1, "USER", "one two"), message(2, "ASSISTANT", "three four"), message(3, "USER", "five six")
        )

        val loaded = context.load("user-1", "session-1", maxMessages = 2, maxTokens = 4)

        assertEquals(AgentSessionSummary(schemaVersion = 1, goals = listOf("glow"), lastSummarizedSequence = 2), loaded.summary)
        assertEquals(listOf("ASSISTANT", "USER"), loaded.messages.map { it.role })
        assertEquals(listOf("three four", "five six"), loaded.messages.map { it.content })
    }

    @Test
    fun `does not expose unfinished turns or another users session`() {
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-2") } returns null

        assertThrows(IllegalArgumentException::class.java) { context.load("user-2", "session-1", 20, 100) }
    }

    @Test
    fun `does not load succeeded messages older than the configured retention window`() {
        every { sessions.findByIdAndUserIdAndDeletedAtIsNull("session-1", "user-1") } returns session()
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(
            message(1, "USER", "expired", LocalDateTime.now().minusDays(8)),
            message(2, "ASSISTANT", "current")
        )

        val loaded = context.load("user-1", "session-1", 20, 100)

        assertEquals(listOf("current"), loaded.messages.map { it.content })
    }

    @Test
    fun `pruning retains deterministic summary but excludes raw safety evidence`() {
        val session = session()
        val old = message(1, "USER", "I am pregnant and have severe allergies", LocalDateTime.now().minusDays(8))
        val newer = message(2, "ASSISTANT", "safe response")
        every { messages.findSucceededTurnMessagesBySessionId("session-1") } returns listOf(old, newer)
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

    private fun session(summary: String = "{}") = ChatSessionEntity(
        id = "session-1", userId = "user-1", persona = "CONSULTANT", contextType = "GENERAL", summaryJson = summary
    )

    private fun turn(
        id: String = "turn-1",
        status: AgentTurnStatus = AgentTurnStatus.PENDING,
        requestHash: String = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    ) = AgentTurnEntity(id = id, sessionId = "session-1", sequenceNo = 1, idempotencyKey = "key-1", requestHash = requestHash, status = status, traceId = "trace-1")

    private fun message(sequenceNo: Long, role: String, content: String, createdAt: LocalDateTime = LocalDateTime.now()) =
        ChatMessageEntity(id = "message-$sequenceNo", sessionId = "session-1", sequenceNo = sequenceNo, role = role, content = content, createdAt = createdAt)
}
