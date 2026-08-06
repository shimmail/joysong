package com.joysong.app.data.remote.dto

// Customer Service DTOs
data class CsConversationDto(
    val id: String = "",
    val userAId: String = "",
    val userBId: String = "",
    val lastMessage: String? = null,
    val lastMessageAt: String? = null,
    val unreadCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class CsMessageDto(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val content: String = "",
    val messageType: String = "TEXT",
    val isRead: Boolean = false,
    val createdAt: String = ""
)

data class SendCsMessageRequest(val content: String, val messageType: String = "TEXT")
