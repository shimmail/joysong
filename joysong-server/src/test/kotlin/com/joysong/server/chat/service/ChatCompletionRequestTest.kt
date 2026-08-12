package com.joysong.server.chat.service

import com.joysong.server.agent.provider.AgentProviderRequestFactory
import com.joysong.server.agent.provider.AgentRequestPurpose
import com.joysong.server.config.AiAgentProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChatCompletionRequestTest {

    private val messages = listOf(mapOf("role" to "user", "content" to "hello"))

    @Test
    fun `QWEN intent request adds deterministic temperature`() {
        val body = AgentProviderRequestFactory.build(
            provider = AiAgentProvider.QWEN,
            model = " qwen-plus ",
            messages = messages,
            maxOutputTokens = 180,
            purpose = AgentRequestPurpose.INTENT
        )

        assertEquals(
            linkedMapOf(
                "model" to "qwen-plus",
                "messages" to messages,
                "stream" to false,
                "max_tokens" to 180,
                "temperature" to 0
            ),
            body
        )
        assertLegacyParametersAreAbsent(body)
    }

    @Test
    fun `QWEN chat request has no temperature regardless of model`() {
        val first = AgentProviderRequestFactory.build(
            AiAgentProvider.QWEN,
            "qwen-plus",
            messages,
            280,
            AgentRequestPurpose.CHAT
        )
        val second = AgentProviderRequestFactory.build(
            AiAgentProvider.QWEN,
            "qwen-max",
            messages,
            280,
            AgentRequestPurpose.CHAT
        )

        assertEquals(first.keys, second.keys)
        assertFalse(first.containsKey("temperature"))
        assertLegacyParametersAreAbsent(first)
    }

    @Test
    fun `OpenAI compatible requests keep the conservative shape for both purposes`() {
        AgentRequestPurpose.entries.forEach { purpose ->
            val body = AgentProviderRequestFactory.build(
                AiAgentProvider.OPENAI_COMPATIBLE,
                " gateway-model ",
                messages,
                280,
                purpose
            )

            assertEquals(
                linkedMapOf(
                    "model" to "gateway-model",
                    "messages" to messages,
                    "stream" to false,
                    "max_tokens" to 280
                ),
                body
            )
            assertLegacyParametersAreAbsent(body)
        }
    }

    private fun assertLegacyParametersAreAbsent(body: Map<String, Any>) {
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
    }
}
