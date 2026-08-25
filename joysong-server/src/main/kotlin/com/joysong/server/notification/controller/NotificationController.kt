package com.joysong.server.notification.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.notification.dto.NotificationResponse
import com.joysong.server.notification.service.NotificationService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/notifications")
class NotificationController(
    private val notificationService: NotificationService
) {

    /**
     * 获取当前用户的通知列表
     */
    @GetMapping
    fun getNotifications(
        authentication: Authentication,
        @RequestParam(required = false, defaultValue = "50") limit: Int
    ): BaseResponse<List<NotificationResponse>> {
        val userId = authentication.principal as String
        val safeLimit = limit.coerceIn(1, 100)
        val notifications = notificationService.getNotifications(userId, safeLimit)
        return BaseResponse.success(notifications)
    }

    /**
     * 获取当前用户的未读通知数量
     */
    @GetMapping("/unread-count")
    fun getUnreadCount(
        authentication: Authentication
    ): BaseResponse<Long> {
        val userId = authentication.principal as String
        val count = notificationService.getUnreadCount(userId)
        return BaseResponse.success(count)
    }

    /**
     * 获取当前用户按消息中心分类汇总的未读通知数量
     */
    @GetMapping("/unread-counts")
    fun getUnreadCounts(
        authentication: Authentication
    ) = BaseResponse.success(
        notificationService.getUnreadCounts(authentication.principal as String)
    )

    /**
     * 标记单条通知为已读
     */
    @PutMapping("/{id}/read")
    fun markAsRead(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            notificationService.markAsRead(id, userId)
            BaseResponse.success(null)
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 404, message = e.message ?: "通知不存在")
        }
    }

    /**
     * 标记当前用户所有通知为已读
     */
    @PutMapping("/read-all")
    fun markAllAsRead(
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        notificationService.markAllAsRead(userId)
        return BaseResponse.success(null)
    }
}
