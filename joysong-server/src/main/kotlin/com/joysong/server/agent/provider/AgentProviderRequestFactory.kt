package com.joysong.server.agent.provider

import com.joysong.server.config.AiAgentProvider

enum class AgentRequestPurpose {
    CHAT,
    INTENT
}

object AgentProviderRequestFactory {
    fun build(
        provider: AiAgentProvider,
        model: String,
        messages: List<Map<String, String>>,
        maxOutputTokens: Int,
        purpose: AgentRequestPurpose
    ): Map<String, Any> = linkedMapOf<String, Any>(
        "model" to model.trim(),
        "messages" to messages,
        "stream" to false,
        "max_tokens" to maxOutputTokens
    ).apply {
        if (provider == AiAgentProvider.QWEN && purpose == AgentRequestPurpose.INTENT) {
            put("temperature", 0)
        }
    }
}
