package com.joysong.server.chat.controller

import com.joysong.server.agent.controller.AgentChatExceptionHandler
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.streaming.AgentStreamEvent
import com.joysong.server.agent.streaming.AgentStreamingService
import com.joysong.server.agent.streaming.AgentStreamSubscription
import com.joysong.server.chat.service.ChatService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ChatStreamingControllerTest {
    private val streaming = mockk<AgentStreamingService>()
    private val subscription = mockk<AgentStreamSubscription>(relaxed = true)
    private val controller = ChatController(mockk<ChatService>(), mockk<TurnLifecycleService>(), streaming)
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(AgentChatExceptionHandler())
        .build()

    @Test
    fun `stream POST exposes SSE wire format named events and authenticated user`() {
        val sink = slot<com.joysong.server.agent.streaming.AgentStreamSink>()
        every { streaming.stream("session-1", "captured-user", any(), capture(sink)) } answers {
            sink.captured.started(AgentStreamEvent.Started("trace-1", "turn-1", message("USER", "hello")))
            sink.captured.delta(AgentStreamEvent.Delta("answer"))
            sink.captured.failed(AgentStreamEvent.Failed("AI_PROVIDER_UNAVAILABLE", "trace-1", true))
            subscription
        }

        val initial = mvc.perform(
            post("/api/chat/sessions/session-1/messages/stream")
                .principal(UsernamePasswordAuthenticationToken("captured-user", "n/a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"hello"}""")
        ).andReturn()
        val response = mvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn().response.contentAsString

        assertTrue(response.contains("event:started"))
        assertTrue(response.contains("event:delta"))
        assertTrue(response.contains("event:failed"))
        assertTrue(response.contains("\"traceId\":\"trace-1\""))
        verify { streaming.stream("session-1", "captured-user", any(), any()) }
    }

    @Test
    fun `pre start failure remains JSON HTTP response`() {
        every { streaming.stream(any(), any(), any(), any()) } throws AgentChatException.sessionNotFound()

        mvc.perform(
            post("/api/chat/sessions/missing/messages/stream")
                .principal(UsernamePasswordAuthenticationToken("captured-user", "n/a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"hello"}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.message").value("SESSION_NOT_FOUND"))
    }

    private fun message(role: String, content: String) = com.joysong.server.chat.dto.ChatMessageResponse(
        id = "message-1", sessionId = "session-1", role = role, content = content, createdAt = "2026-08-13T00:00:00"
    )
}
