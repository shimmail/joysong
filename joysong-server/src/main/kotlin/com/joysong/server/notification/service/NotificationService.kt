package com.joysong.server.notification.service

import com.joysong.server.notification.dto.NotificationResponse
import com.joysong.server.notification.dto.toResponse
import com.joysong.server.notification.entity.NotificationEntity
import com.joysong.server.notification.repository.NotificationRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository
) {

    /**
     * 获取用户的通知列表
     */
    fun getNotifications(userId: String, limit: Int = 50): List<NotificationResponse> {
        val pageable = PageRequest.of(0, limit)
        val locale = LocaleContextHolder.getLocale()
        return notificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
            .map { NotificationTextProjection.localize(it.toResponse(), locale) }
    }

    /**
     * 获取用户未读通知数量
     */
    fun getUnreadCount(userId: String): Long {
        return notificationRepository.countByUserIdAndIsReadAndDeletedAtIsNull(userId, false)
    }

    /**
     * 标记单条通知为已读
     */
    @Transactional
    fun markAsRead(notificationId: String, userId: String) {
        val notification = notificationRepository.findByIdAndUserIdAndDeletedAtIsNull(notificationId, userId)
            ?: throw IllegalArgumentException("通知不存在或无权访问")
        notification.isRead = true
        notificationRepository.save(notification)
    }

    /**
     * 标记用户所有通知为已读
     */
    @Transactional
    fun markAllAsRead(userId: String) {
        notificationRepository.markAllAsReadByUserId(userId)
    }

    /**
     * 创建通知（供其他 Service 调用）
     */
    @Transactional
    fun createNotification(
        userId: String,
        type: String,
        title: String,
        content: String = "",
        targetType: String = "",
        targetId: String = ""
    ): NotificationEntity {
        require(userId.isNotBlank()) { "userId 不能为空" }
        require(type.isNotBlank()) { "type 不能为空" }
        require(title.isNotBlank()) { "title 不能为空" }

        val notification = NotificationEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            type = type,
            title = title,
            content = content,
            targetType = targetType,
            targetId = targetId,
            isRead = false,
            createdAt = LocalDateTime.now()
        )
        return notificationRepository.save(notification)
    }

    // ========== 管理员方法 ==========

    /**
     * 管理员分页查询通知列表
     */
    fun adminList(type: String?, isRead: Boolean?, keyword: String?, pageable: Pageable): Page<NotificationResponse> {
        val page = if (!keyword.isNullOrBlank()) {
            notificationRepository.adminFindAllWithKeyword(type, isRead, keyword.trim(), pageable)
        } else {
            notificationRepository.adminFindAll(type, isRead, pageable)
        }
        return page.map { it.toResponse() }
    }

    /**
     * 管理员标记单条通知已读
     */
    @Transactional
    fun adminMarkAsRead(id: String): Boolean {
        val notification = notificationRepository.findById(id).orElse(null) ?: return false
        if (notification.deletedAt != null) return false
        notification.isRead = true
        notificationRepository.save(notification)
        return true
    }

    /**
     * 管理员标记所有通知已读
     */
    @Transactional
    fun adminMarkAllAsRead() {
        // 将所有未删除且未读的通知标记为已读
        notificationRepository.adminMarkAllAsRead()
    }

    /**
     * 管理员软删除单条通知
     */
    @Transactional
    fun adminSoftDelete(id: String): Boolean {
        val notification = notificationRepository.findById(id).orElse(null) ?: return false
        if (notification.deletedAt != null) return true // 已删除
        notification.deletedAt = LocalDateTime.now()
        notificationRepository.save(notification)
        return true
    }

    /**
     * 管理员批量软删除通知
     */
    @Transactional
    fun adminBatchSoftDelete(ids: List<String>): Int {
        val notifications = notificationRepository.findAllById(ids)
        var count = 0
        val now = LocalDateTime.now()
        notifications.forEach { n ->
            if (n.deletedAt == null) {
                n.deletedAt = now
                notificationRepository.save(n)
                count++
            }
        }
        return count
    }
}
