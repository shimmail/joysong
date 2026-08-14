package com.joysong.server.agent

import com.joysong.server.agent.streaming.AgentStreamEvent
import com.joysong.server.agent.streaming.AgentStreamSink
import com.joysong.server.agent.streaming.AgentStreamingService
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.ComparisonOperand
import com.joysong.server.agent.service.ComparisonRequest
import com.joysong.server.chat.dto.ChatMessageResponse
import com.joysong.server.chat.dto.ChatTurnResponse
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.service.ChatService
import com.joysong.server.chat.service.ChatTurnResult
import com.joysong.server.chat.service.PreparedChatTurn
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.config.AiAgentProvider
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.InputStream

class AgentStreamingServiceTest {
    private val chatService = mockk<ChatService>()
    private val restTemplate = RestTemplate()
    private val provider = MockRestServiceServer.bindTo(restTemplate).build()
    private val properties = AiAgentProperties(
        provider = AiAgentProvider.QWEN,
        apiKey = "test-key",
        baseUrl = "https://provider.test/v1",
        model = "qwen-test"
    )

    @Test
    fun `normal stream emits started deltas then completed after persistence`() {
        val prepared = prepared(planning = false)
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage("session-1", "user-1", any()) } returns prepared
        every { chatService.completeStreamingMessage(prepared, "hello") } answers {
            sink.record("persisted")
            completedTurn("hello")
        }
        provider.expect(content().json("""{"stream":true}""", false))
            .andRespond(withSuccess(sse("hel", "lo"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question", "key-1"), sink)

        assertEquals(
            listOf("started", "delta:hel", "delta:lo", "persisted", "completed"),
            sink.order
        )
        assertEquals("hello", (sink.events.last() as AgentStreamEvent.Completed).turn.message.content)
        verify(exactly = 1) { chatService.completeStreamingMessage(prepared, "hello") }
        provider.verify()
    }

    @Test
    fun `stream completion binds comparison state and report to assistant message`() {
        val prepared = prepared(planning = false)
        val sink = RecordingSink()
        val completed = comparisonTurn("hello")
        every { chatService.prepareStreamingMessage("session-1", "user-1", any()) } returns prepared
        every { chatService.completeStreamingMessage(prepared, "hello") } returns completed
        provider.expect(content().json("""{"stream":true}""", false))
            .andRespond(withSuccess(sse("hello"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("compare", "comparison-key"), sink)

        val event = sink.events.last() as AgentStreamEvent.Completed
        assertEquals(completed.comparisonRequest, event.turn.message.comparisonRequest)
        assertEquals(completed.catalogReport, event.turn.message.catalogReport)
        assertEquals(event.turn.catalogReport, event.turn.message.catalogReport)
        assertEquals(completed.catalogItems, event.turn.message.catalogItems)
        provider.verify()
    }

    @Test
    fun `successful idempotent replay emits original started then completed without provider or persistence`() {
        val userMessage = prepared().userMessage
        val completed = comparisonTurn("persisted answer")
        val replayed = PreparedChatTurn.Replayed(
            traceId = "original-trace",
            turnId = "original-turn",
            userMessage = userMessage,
            turn = completed
        )
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage("session-1", "user-1", any()) } returns replayed

        service().stream("session-1", "user-1", SendMessageRequest("question", "same-key"), sink)

        assertEquals(listOf("started", "completed"), sink.order)
        val started = sink.events.first() as AgentStreamEvent.Started
        assertEquals("original-trace", started.traceId)
        assertEquals("original-turn", started.turnId)
        assertEquals("user-message-1", started.userMessage.id)
        assertEquals("question", started.userMessage.content)
        assertEquals(null, started.userMessage.comparisonRequest)
        assertEquals(null, started.userMessage.catalogReport)
        val terminal = sink.events.last() as AgentStreamEvent.Completed
        assertEquals(completed.comparisonRequest, terminal.turn.message.comparisonRequest)
        assertEquals(completed.catalogReport, terminal.turn.message.catalogReport)
        assertEquals(terminal.turn.catalogReport, terminal.turn.message.catalogReport)
        assertEquals(completed.catalogItems, terminal.turn.message.catalogItems)
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
        verify(exactly = 0) { chatService.failStreamingMessage(any(), any()) }
        verify(exactly = 0) { chatService.cancelStreamingMessage(any(), any()) }
        provider.verify()
    }

    @Test
    fun `deterministic incomplete comparison emits started and completed without opening provider stream`() {
        val userMessage = prepared().userMessage
        val turn = comparisonTurn("请选择至少两个对比对象 / Select at least two items to compare")
        val completed = PreparedChatTurn.Completed(
            traceId = "trace-local",
            turnId = "turn-local",
            userMessage = userMessage,
            turn = turn
        )
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage("session-1", "user-1", any()) } returns completed

        service().stream("session-1", "user-1", SendMessageRequest("Compare clinics"), sink)

        assertEquals(listOf("started", "completed"), sink.order)
        assertEquals(
            "请选择至少两个对比对象 / Select at least two items to compare",
            (sink.events.last() as AgentStreamEvent.Completed).turn.message.content
        )
        val terminal = sink.events.last() as AgentStreamEvent.Completed
        assertEquals(turn.comparisonRequest, terminal.turn.message.comparisonRequest)
        assertEquals(turn.catalogReport, terminal.turn.message.catalogReport)
        assertEquals(terminal.turn.catalogReport, terminal.turn.message.catalogReport)
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
        verify(exactly = 0) { chatService.failStreamingMessage(any(), any()) }
        verify(exactly = 0) { chatService.cancelStreamingMessage(any(), any()) }
        provider.verify()
    }

    @Test
    fun `planning buffers the full provider answer and never exposes raw deltas`() {
        val prepared = prepared(planning = true)
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.completeStreamingMessage(prepared, "unsafe raw plan") } returns completedTurn("safe plan")
        provider.expect { }.andRespond(withSuccess(sse("unsafe ", "raw plan"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("plan"), sink)

        assertFalse(sink.events.any { it is AgentStreamEvent.Delta })
        assertEquals("safe plan", (sink.events.last() as AgentStreamEvent.Completed).turn.message.content)
    }

    @Test
    fun `provider failure fails the turn without completion and emits one terminal event`() {
        val prepared = prepared()
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.failStreamingMessage(prepared, any()) } returns "AI_PROVIDER_UNAVAILABLE"
        provider.expect { }.andRespond(withServerError())

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        assertEquals(1, sink.events.count { it is AgentStreamEvent.Failed || it is AgentStreamEvent.Completed })
        assertEquals("AI_PROVIDER_UNAVAILABLE", (sink.events.last() as AgentStreamEvent.Failed).code)
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
        verify(exactly = 1) { chatService.failStreamingMessage(prepared, any()) }
    }

    @Test
    fun `unfinished provider stream fails without persisting an assistant message`() {
        val prepared = prepared()
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.failStreamingMessage(prepared, any()) } returns "AI_PROVIDER_UNAVAILABLE"
        provider.expect { }.andRespond(withSuccess("data: {malformed}\n\n", MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        assertTrue(sink.events.last() is AgentStreamEvent.Failed)
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
    }

    @Test
    fun `closed sink cancels the turn and emits no second terminal event`() {
        val prepared = prepared()
        val sink = RecordingSink(closeAfterFirstDelta = true)
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "CLIENT_DISCONNECTED") } just runs
        provider.expect { }.andRespond(withSuccess(sse("first", "second"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        assertEquals(listOf("started", "delta:first"), sink.order)
        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "CLIENT_DISCONNECTED") }
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
        verify(exactly = 0) { chatService.failStreamingMessage(any(), any()) }
    }

    @Test
    fun `sink send failure finalizes the turn once`() {
        val prepared = prepared()
        val sink = RecordingSink(throwOnDelta = true)
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.failStreamingMessage(prepared, any()) } returns "AGENT_STREAM_SEND_FAILED"
        provider.expect { }.andRespond(withSuccess(sse("first", "second"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        verify(exactly = 1) { chatService.failStreamingMessage(prepared, any()) }
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
        assertEquals(1, sink.events.count { it is AgentStreamEvent.Completed || it is AgentStreamEvent.Failed })
        assertTrue(sink.events.last() is AgentStreamEvent.Failed)
    }

    @Test
    fun `sink failure still emits one stable failed event when failure persistence throws`() {
        val prepared = prepared()
        val sink = RecordingSink(throwOnDelta = true)
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.failStreamingMessage(prepared, any()) } throws IllegalStateException("fail failed")
        provider.expect { }.andRespond(withSuccess(sse("first"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        verify(exactly = 1) { chatService.failStreamingMessage(prepared, any()) }
        assertEquals(1, sink.events.count { it is AgentStreamEvent.Failed })
        assertEquals("AGENT_INTERNAL_ERROR", (sink.events.last() as AgentStreamEvent.Failed).code)
    }

    @Test
    fun `disconnect that wins terminal ownership prevents assistant persistence`() {
        val prepared = prepared()
        val sink = RecordingSink()
        val pending = AtomicReference<Runnable>()
        val executor = TaskExecutor { pending.set(it) }
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "CLIENT_DISCONNECTED") } just runs

        val subscription = AgentStreamingService(chatService, restTemplate, properties, executor)
            .stream("session-1", "user-1", SendMessageRequest("question"), sink)
        subscription.cancel("CLIENT_DISCONNECTED")
        pending.get().run()

        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "CLIENT_DISCONNECTED") }
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
    }

    @Test
    fun `cancelling subscription interrupts an in flight provider task`() {
        val prepared = prepared()
        val sink = RecordingSink()
        val pending = AtomicReference<Runnable>()
        val executor = TaskExecutor { pending.set(it) }
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") } just runs

        val subscription = AgentStreamingService(chatService, restTemplate, properties, executor)
            .stream("session-1", "user-1", SendMessageRequest("question"), sink)
        subscription.cancel("STREAM_TIMEOUT")

        assertTrue(subscription.isCancelled)
        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") }
    }

    @Test
    fun `completion failure fails the turn and emits exactly one failed event`() {
        val prepared = prepared()
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.completeStreamingMessage(prepared, "answer") } throws IllegalStateException("database")
        every { chatService.failStreamingMessage(prepared, any()) } returns "AGENT_INTERNAL_ERROR"
        provider.expect { }.andRespond(withSuccess(sse("answer"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        verify(exactly = 1) { chatService.failStreamingMessage(prepared, any()) }
        assertEquals(1, sink.events.count { it is AgentStreamEvent.Failed || it is AgentStreamEvent.Completed })
        assertTrue(sink.events.last() is AgentStreamEvent.Failed)
    }

    @Test
    fun `cancel closes blocking upstream stream and worker exits without persistence`() {
        val prepared = prepared()
        val sink = RecordingSink()
        val blocking = BlockingInputStream()
        val workerDone = CountDownLatch(1)
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") } just runs
        val streamService = AgentStreamingService(
            chatService,
            restTemplate,
            properties,
            TaskExecutor { task -> Thread { try { task.run() } finally { workerDone.countDown() } }.start() },
            { _, _, _ -> blocking }
        )

        val subscription = streamService.stream("session-1", "user-1", SendMessageRequest("question"), sink)
        assertTrue(blocking.entered.await(2, TimeUnit.SECONDS))
        subscription.cancel("STREAM_TIMEOUT")

        assertTrue(blocking.closed.await(2, TimeUnit.SECONDS))
        assertTrue(workerDone.await(2, TimeUnit.SECONDS))
        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") }
        verify(exactly = 0) { chatService.completeStreamingMessage(any(), any()) }
    }

    @Test
    fun `upstream registered after cancellation is closed immediately`() {
        val prepared = prepared()
        val sink = RecordingSink()
        val releaseOpen = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val stream = BlockingInputStream()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") } just runs
        val streamService = AgentStreamingService(chatService, restTemplate, properties, TaskExecutor { task -> Thread(task).start() }) { _, _, _ ->
            opened.countDown()
            while (releaseOpen.count > 0) runCatching { releaseOpen.await() }
            stream
        }

        val subscription = streamService.stream("session-1", "user-1", SendMessageRequest("question"), sink)
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        subscription.cancel("STREAM_TIMEOUT")
        releaseOpen.countDown()

        assertTrue(stream.closed.await(2, TimeUnit.SECONDS))
        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") }
    }

    @Test
    fun `upstream close failure still cancels turn`() {
        val prepared = prepared()
        val sink = RecordingSink()
        val entered = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val failingClose = object : InputStream() {
            override fun read(): Int { entered.countDown(); Thread.sleep(5_000); return -1 }
            override fun close() { closeAttempted.countDown(); throw IllegalStateException("close failed") }
        }
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") } just runs
        val streamService = AgentStreamingService(chatService, restTemplate, properties, TaskExecutor { task -> Thread(task).start() }) { _, _, _ -> failingClose }

        val subscription = streamService.stream("session-1", "user-1", SendMessageRequest("question"), sink)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        subscription.cancel("STREAM_TIMEOUT")

        assertTrue(closeAttempted.await(2, TimeUnit.SECONDS))
        verify(exactly = 1) { chatService.cancelStreamingMessage(prepared, "STREAM_TIMEOUT") }
    }

    @Test
    fun `finalization and fail persistence errors still emit one failed terminal event`() {
        val prepared = prepared()
        val sink = RecordingSink()
        every { chatService.prepareStreamingMessage(any(), any(), any()) } returns prepared
        every { chatService.completeStreamingMessage(prepared, "answer") } throws IllegalStateException("complete failed")
        every { chatService.failStreamingMessage(prepared, any()) } throws IllegalStateException("fail failed")
        provider.expect { }.andRespond(withSuccess(sse("answer"), MediaType.TEXT_EVENT_STREAM))

        service().stream("session-1", "user-1", SendMessageRequest("question"), sink)

        assertEquals(1, sink.events.count { it is AgentStreamEvent.Failed })
        assertEquals("AGENT_INTERNAL_ERROR", (sink.events.last() as AgentStreamEvent.Failed).code)
    }

    private class BlockingInputStream : InputStream() {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun read(): Int {
            entered.countDown()
            closed.await()
            return -1
        }
        override fun close() {
            closed.countDown()
        }
    }

    private fun service() = AgentStreamingService(chatService, restTemplate, properties, SyncTaskExecutor())

    private fun prepared(planning: Boolean = false) = PreparedChatTurn.Started(
        traceId = "trace-1",
        turnId = "turn-1",
        userMessage = ChatMessageEntity(
            id = "user-message-1",
            sessionId = "session-1",
            role = "USER",
            content = "question"
        ),
        messages = listOf(mapOf("role" to "user", "content" to "question")),
        maxOutputTokens = 100,
        planning = planning
    )

    private fun completedTurn(content: String) = ChatTurnResult(
        message = ChatMessageEntity(
            id = "assistant-message-1",
            sessionId = "session-1",
            role = "ASSISTANT",
            content = content
        ),
        traceId = "trace-1"
    )

    private fun comparisonTurn(content: String): ChatTurnResult {
        val items = listOf(
            comparisonItem("project-alpha", "Alpha"),
            comparisonItem("project-beta", "Beta")
        )
        val report = AgentCatalogReportResponse(
            mode = "COMPARISON",
            title = "Project comparison",
            summary = "Structured comparison",
            items = items,
            comparisonDimensions = listOf("Reference price", "Rating"),
            warnings = emptyList()
        )
        return ChatTurnResult(
            message = ChatMessageEntity(
                id = "assistant-message-1",
                sessionId = "session-1",
                role = "ASSISTANT",
                content = content
            ),
            catalogReport = report,
            catalogItems = items,
            intent = "COMPARISON",
            queryTarget = "PROJECT",
            nextAction = "SHOW_COMPARISON",
            traceId = "trace-1",
            comparisonRequest = ComparisonRequest(
                operands = listOf(
                    ComparisonOperand(AgentQueryTarget.PROJECT, "project-alpha", "Alpha"),
                    ComparisonOperand(AgentQueryTarget.PROJECT, "project-beta", "Beta")
                ),
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf("PRICE", "RATING")
            )
        )
    }

    private fun comparisonItem(id: String, name: String) = AgentCatalogItemResponse(
        type = "PROJECT",
        id = id,
        name = name,
        subtitle = "",
        summary = "",
        attributes = linkedMapOf("Reference price" to "1000", "Rating" to "4.8")
    )

    private fun sse(vararg chunks: String): String = chunks.joinToString("\n\n", postfix = "\n\ndata: [DONE]\n\n") {
        "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}"
    }

    private class RecordingSink(
        private val closeAfterFirstDelta: Boolean = false,
        private val throwOnDelta: Boolean = false
    ) : AgentStreamSink {
        val events = mutableListOf<AgentStreamEvent>()
        val order = mutableListOf<String>()
        private var open = true

        override val isOpen: Boolean get() = open

        override fun started(event: AgentStreamEvent.Started) = add(event, "started")

        override fun delta(event: AgentStreamEvent.Delta) {
            if (throwOnDelta) throw IllegalStateException("emitter closed")
            add(event, "delta:${event.content}")
            if (closeAfterFirstDelta) open = false
        }

        override fun completed(event: AgentStreamEvent.Completed) = add(event, "completed")

        override fun failed(event: AgentStreamEvent.Failed) = add(event, "failed")

        fun record(value: String) {
            order += value
        }

        private fun add(event: AgentStreamEvent, label: String) {
            events += event
            order += label
        }
    }
}
