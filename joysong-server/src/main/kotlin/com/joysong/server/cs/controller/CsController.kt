package com.joysong.server.cs.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.cs.dto.CsSendMessageRequest
import com.joysong.server.cs.service.CsService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/cs")
class CsController(private val csService: CsService) {

    /**
     * 创建或获取当前用户与客服的会话。
     * POST /api/cs/conversations
     */
    @PostMapping("/conversations")
    fun getOrCreateConversation(authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(csService.getOrCreateConversation(userId))
    }

    /**
     * 获取当前用户的所有客服会话列表。
     * GET /api/cs/conversations
     */
    @GetMapping("/conversations")
    fun listConversations(authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(csService.getConversations(userId))
    }

    /**
     * 向指定客服会话发送消息。
     * POST /api/cs/conversations/{id}/messages
     */
    @PostMapping("/conversations/{id}/messages")
    fun sendMessage(
        @PathVariable id: String,
        @RequestBody body: CsSendMessageRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        return try {
            val userId = authentication.principal as String
            BaseResponse.success(csService.sendMessage(id, userId, body.content, body.messageType))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }

    /**
     * 获取指定客服会话的消息历史。
     * GET /api/cs/conversations/{id}/messages?limit=50
     */
    @GetMapping("/conversations/{id}/messages")
    fun listMessages(
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        before: LocalDateTime?,
        authentication: Authentication
    ): BaseResponse<*> {
        return try {
            val userId = authentication.principal as String
            BaseResponse.success(csService.getMessages(id, userId, limit, before))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }

    /**
     * 将客服发来的消息标记为已读。
     * PUT /api/cs/conversations/{id}/read
     */
    @PutMapping("/conversations/{id}/read")
    fun markAsRead(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        return try {
            val userId = authentication.principal as String
            csService.markAsRead(id, userId)
            BaseResponse.success(null)
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "参数错误")
        }
    }
}
