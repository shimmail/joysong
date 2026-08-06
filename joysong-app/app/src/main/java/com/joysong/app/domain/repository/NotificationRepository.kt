package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Notification

interface NotificationRepository {
    suspend fun getNotifications(): Result<List<Notification>>
    suspend fun markAsRead(notificationId: String): Result<Unit>
}
