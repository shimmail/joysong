package com.joysong.server.chat.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChatCompletionRequestTest {

    private val messages = listOf(mapOf("role" to "user", "content" to "hello"))

    @Test
    fun `GPT-5 uses gateway compatible token parameter only`() {
        val body = buildChatCompletionRequest(
            model = " gpt-5.5 ",
            messages = messages,
            maxOutputTokens = 280
        )

        assertEquals("gpt-5.5", body["model"])
        assertEquals(280, body["max_tokens"])
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("temperature"))
    }

    @Test
    fun `non GPT-5 uses the same gateway compatible request shape`() {
        val body = buildChatCompletionRequest(
            model = "test-model",
            messages = messages,
            maxOutputTokens = 180
        )

        assertEquals(180, body["max_tokens"])
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("temperature"))
    }
}
