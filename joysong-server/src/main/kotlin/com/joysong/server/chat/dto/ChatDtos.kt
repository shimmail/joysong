package com.joysong.server.chat.dto

import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.dto.AgentCatalogItemResponse

data class CreateSessionRequest(
    val persona: String = "BESTIE",          // BESTIE | CONSULTANT
    val contextType: String = "GENERAL",     // DOCTOR | PROJECT | GENERAL
    val contextId: String = "",
    val title: String = ""
)

data class SendMessageRequest(
    val content: String
)

data class ChatSessionResponse(
    val id: String,
    val persona: String,
    val contextType: String,
    val contextId: String,
    val title: String,
    val lastMessage: String = "",
    val createdAt: String,
    val updatedAt: String
)

data class ChatMessageResponse(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: String
)

data class ChatTurnResponse(
    val message: ChatMessageResponse,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE"
)
