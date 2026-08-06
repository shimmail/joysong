package com.joysong.server.admin.controller

import com.joysong.server.admin.service.AdminCustomerServiceService
import com.joysong.server.admin.service.CsSendRequest
import com.joysong.server.common.BaseResponse
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/cs")
class AdminCustomerServiceController(
    private val csService: AdminCustomerServiceService
) {

    /**
     * 获取所有客服会话列表，可按用户昵称关键字过滤。
     * GET /api/admin/cs/conversations?keyword=xxx
     */
    @GetMapping("/conversations")
    fun listConversations(@RequestParam(required = false) keyword: String?): BaseResponse<*> {
        return BaseResponse.success(csService.getAllConversations(keyword))
    }

    /**
     * 获取指定会话的消息历史。
     * GET /api/admin/cs/conversations/{id}/messages?limit=50
     */
    @GetMapping("/conversations/{id}/messages")
    fun listMessages(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        return try {
            BaseResponse.success(csService.getConversationMessages(id, limit))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }

    /**
     * 以平台客服身份向指定会话发送消息。
     * POST /api/admin/cs/conversations/{id}/messages
     */
    @PostMapping("/conversations/{id}/messages")
    fun sendMessage(
        @PathVariable id: String,
        @RequestBody body: CsSendRequest
    ): BaseResponse<*> {
        return try {
            BaseResponse.success(csService.sendMessage(id, body.content))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }

    /**
     * 将指定会话的客服侧消息标记为已读。
     * PUT /api/admin/cs/conversations/{id}/read
     */
    @PutMapping("/conversations/{id}/read")
    fun markAsRead(@PathVariable id: String): BaseResponse<*> {
        return try {
            csService.markConversationAsRead(id)
            BaseResponse.success(null)
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }
}
