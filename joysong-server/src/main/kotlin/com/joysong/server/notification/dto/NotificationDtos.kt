package com.joysong.server.notification.dto

import com.joysong.server.notification.entity.NotificationEntity

data class NotificationResponse(
    val id: String,
    val userId: String,
    val type: String,
    val title: String,
    val content: String,
    val targetType: String,
    val targetId: String,
    val isRead: Boolean,
    val createdAt: String
)

data class NotificationUnreadCountsResponse(
    val total: Long,
    val system: Long,
    val activity: Long
)

fun NotificationEntity.toResponse(): NotificationResponse {
    return NotificationResponse(
        id = id,
        userId = userId,
        type = type,
        title = title,
        content = content,
        targetType = targetType,
        targetId = targetId,
        isRead = isRead,
        createdAt = createdAt.toString()
    )
}
