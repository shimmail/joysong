package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.notification.service.NotificationService
import org.springframework.data.domain.PageRequest
import org.springframework.web.bind.annotation.*

/**
 * 管理后台通知管理控制器
 * 提供管理员视角的通知列表查看、标记已读、软删除等操作
 */
@RestController
@RequestMapping("/api/admin/notices")
class AdminNotificationController(
    private val notificationService: NotificationService
) {

    /**
     * 分页查询通知列表
     * @param type 通知类型筛选（可选）
     * @param isRead 已读状态筛选（可选）
     * @param keyword 关键词搜索：匹配用户昵称、通知标题、通知内容（可选）
     * @param page 页码（从0开始）
     * @param size 每页数量
     */
    @GetMapping
    fun listNotifications(
        @RequestParam(required = false) type: String?,
        @RequestParam(required = false) isRead: Boolean?,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        val pageable = PageRequest.of(safePage, safeSize)
        val result = notificationService.adminList(type, isRead, keyword, pageable)
        return BaseResponse.success(
            mapOf(
                "content" to result.content,
                "totalElements" to result.totalElements,
                "totalPages" to result.totalPages,
                "number" to result.number,
                "size" to result.size
            )
        )
    }

    /**
     * 标记单条通知为已读
     */
    @PutMapping("/{id}/read")
    fun markAsRead(@PathVariable id: String): BaseResponse<*> {
        val success = notificationService.adminMarkAsRead(id)
        return if (success) BaseResponse.success(null)
        else BaseResponse.error<Any>("通知不存在或已删除", 404)
    }

    /**
     * 标记所有通知为已读
     */
    @PutMapping("/read-all")
    fun markAllAsRead(): BaseResponse<*> {
        notificationService.adminMarkAllAsRead()
        return BaseResponse.success(null)
    }

    /**
     * 软删除单条通知
     */
    @DeleteMapping("/{id}")
    fun deleteNotification(@PathVariable id: String): BaseResponse<*> {
        val success = notificationService.adminSoftDelete(id)
        return if (success) BaseResponse.success(null)
        else BaseResponse.error<Any>("通知不存在", 404)
    }

    /**
     * 批量软删除通知
     */
    @PostMapping("/batch-delete")
    fun batchDelete(@RequestBody body: BatchDeleteRequest): BaseResponse<*> {
        if (body.ids.isEmpty()) {
            return BaseResponse.error<Any>("ids 不能为空")
        }
        val deletedCount = notificationService.adminBatchSoftDelete(body.ids)
        return BaseResponse.success(mapOf("deletedCount" to deletedCount))
    }
}

data class BatchDeleteRequest(val ids: List<String> = emptyList())
