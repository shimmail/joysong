package com.joysong.server.agent.orchestration

import com.joysong.server.config.AiAgentProperties
import org.springframework.stereotype.Component

@Component
class AiAgentAvailabilityGuard(
    private val properties: AiAgentProperties
) {
    fun requireGenerationEnabled() {
        if (!properties.enabled) throw AgentChatException.disabled()
    }
}
