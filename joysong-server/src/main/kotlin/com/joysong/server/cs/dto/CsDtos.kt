package com.joysong.server.cs.dto

data class CsConversationResponse(
    val id: String,
    val userAId: String,
    val userBId: String,
    val lastMessage: String?,
    val lastMessageAt: String?,
    val unreadCount: Int,
    val createdAt: String,
    val updatedAt: String
)

data class CsMessageResponse(
    val id: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val messageType: String,
    val isRead: Boolean,
    val createdAt: String
)

data class CsSendMessageRequest(val content: String, val messageType: String = "TEXT")
