package com.joysong.app.domain.model

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val content: String,
    val isUser: Boolean,
    val role: String = "",
    val createdAt: String = ""
)
