package com.joysong.server.dm.dto

import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.entity.DmMessageEntity

data class DmConversationResponse(
    val id: String,
    val userAId: String,
    val userBId: String,
    val lastMessage: String?,
    val lastMessageAt: String?,
    val userAUnread: Int,
    val userBUnread: Int,
    val createdAt: String,
    val updatedAt: String
)

data class DmMessageResponse(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val content: String,
    val messageType: String,
    val isRead: Boolean,
    val createdAt: String
)

data class SendDmMessageRequest(
    val content: String,
    val messageType: String = "TEXT"
)

data class CreateDmConversationRequest(
    val targetId: String
)

fun DmConversationEntity.toResponse(): DmConversationResponse {
    return DmConversationResponse(
        id = id,
        userAId = userAId,
        userBId = userBId,
        lastMessage = lastMessage,
        lastMessageAt = lastMessageAt?.toString(),
        userAUnread = userAUnread,
        userBUnread = userBUnread,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )
}

fun DmMessageEntity.toResponse(): DmMessageResponse {
    return DmMessageResponse(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        content = content,
        messageType = messageType,
        isRead = isRead,
        createdAt = createdAt.toString()
    )
}
