package com.joysong.server.chat.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChatCompletionRequestTest {

    private val messages = listOf(mapOf("role" to "user", "content" to "hello"))

    @Test
    fun `GPT-5 uses completion token and reasoning parameters without temperature`() {
        val body = buildChatCompletionRequest(
            model = " gpt-5.5 ",
            messages = messages,
            maxOutputTokens = 280,
            temperature = 0.25,
            reasoningEffort = "none"
        )

        assertEquals(280, body["max_completion_tokens"])
        assertEquals("none", body["reasoning_effort"])
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun `non GPT-5 keeps legacy token and temperature parameters`() {
        val body = buildChatCompletionRequest(
            model = "test-model",
            messages = messages,
            maxOutputTokens = 180,
            temperature = 0,
            reasoningEffort = null
        )

        assertEquals(180, body["max_tokens"])
        assertEquals(0, body["temperature"])
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
    }
}
