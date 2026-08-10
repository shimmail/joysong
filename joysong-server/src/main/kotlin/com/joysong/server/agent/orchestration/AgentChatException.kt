package com.joysong.server.agent.orchestration

class AgentChatException(
    val code: String,
    val traceId: String? = null
) : RuntimeException(code) {
    companion object {
        fun sessionNotFound() = AgentChatException("SESSION_NOT_FOUND")
        fun turnInProgress() = AgentChatException("TURN_IN_PROGRESS")
        fun idempotencyConflict() = AgentChatException("IDEMPOTENCY_KEY_CONFLICT")
        fun idempotencyExpired() = AgentChatException("IDEMPOTENCY_EXPIRED")
    }
}
