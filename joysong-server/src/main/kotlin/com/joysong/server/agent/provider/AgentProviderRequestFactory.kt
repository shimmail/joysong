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
        purpose: AgentRequestPurpose,
        streaming: Boolean = false
    ): Map<String, Any> = linkedMapOf<String, Any>(
        "model" to model.trim(),
        "messages" to messages,
        "stream" to streaming,
        "max_tokens" to maxOutputTokens
    ).apply {
        require(!streaming || purpose == AgentRequestPurpose.CHAT) {
            "Streaming is only supported for chat requests"
        }
        if (provider == AiAgentProvider.QWEN && purpose == AgentRequestPurpose.INTENT) {
            put("temperature", 0)
        }
    }
}
