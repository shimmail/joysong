package com.joysong.server.chat.controller

import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.streaming.AgentStreamEvent
import com.joysong.server.agent.streaming.AgentStreamingService
import com.joysong.server.agent.streaming.AgentStreamSubscription
import com.joysong.server.chat.service.ChatService
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class ChatStreamingControllerTest {
    private val streaming = mockk<AgentStreamingService>()
    private val subscription = mockk<AgentStreamSubscription>()
    private val controller = ChatController(mockk<ChatService>(), mockk<TurnLifecycleService>(), streaming)
    private val auth = UsernamePasswordAuthenticationToken("captured-user", "n/a")

    @Test
    fun `captures authenticated user before streaming starts`() {
        every { streaming.stream("session-1", "captured-user", any(), any()) } returns subscription

        val emitter = controller.streamMessage(auth, "session-1", com.joysong.server.chat.dto.SendMessageRequest("hello"))

        assertEquals(60_000L, emitter.timeout)
        verify { streaming.stream("session-1", "captured-user", any(), any()) }
    }

    @Test
    fun `pre start error remains a synchronous HTTP exception`() {
        every { streaming.stream(any(), any(), any(), any()) } throws AgentChatException.sessionNotFound()

        val error = assertThrows(AgentChatException::class.java) {
            controller.streamMessage(auth, "missing", com.joysong.server.chat.dto.SendMessageRequest("hello"))
        }

        assertEquals("SESSION_NOT_FOUND", error.code)
    }

    @Test
    fun `post start failure is sent as named SSE event`() {
        val sink = slot<com.joysong.server.agent.streaming.AgentStreamSink>()
        every { streaming.stream(any(), any(), any(), capture(sink)) } answers {
            sink.captured.failed(AgentStreamEvent.Failed("AI_PROVIDER_UNAVAILABLE", "trace-1", true))
            subscription
        }

        val emitter = controller.streamMessage(auth, "session-1", com.joysong.server.chat.dto.SendMessageRequest("hello"))

        assertEquals(60_000L, emitter.timeout)
    }

    @Test
    fun `emitter completion callback cancels the in flight subscription`() {
        every { subscription.cancel("CLIENT_DISCONNECTED") } just runs
        every { streaming.stream(any(), any(), any(), any()) } returns subscription
        val emitter = controller.streamMessage(auth, "session-1", com.joysong.server.chat.dto.SendMessageRequest("hello"))
        val callback = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter::class.java
            .getDeclaredField("completionCallback")
            .apply { isAccessible = true }
            .get(emitter) as Runnable
        callback.run()

        verify { subscription.cancel("CLIENT_DISCONNECTED") }
    }
}
