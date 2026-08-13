package com.joysong.server.agent.streaming

import com.joysong.server.chat.dto.ChatMessageResponse
import com.joysong.server.chat.dto.ChatTurnResponse

sealed interface AgentStreamEvent {
    data class Started(
        val traceId: String,
        val turnId: String,
        val userMessage: ChatMessageResponse
    ) : AgentStreamEvent

    data class Delta(val content: String) : AgentStreamEvent

    data class Completed(val turn: ChatTurnResponse) : AgentStreamEvent

    data class Failed(
        val code: String,
        val traceId: String?,
        val retryable: Boolean
    ) : AgentStreamEvent
}

interface AgentStreamSink {
    val isOpen: Boolean
    fun started(event: AgentStreamEvent.Started)
    fun delta(event: AgentStreamEvent.Delta)
    fun completed(event: AgentStreamEvent.Completed)
    fun failed(event: AgentStreamEvent.Failed)
}
