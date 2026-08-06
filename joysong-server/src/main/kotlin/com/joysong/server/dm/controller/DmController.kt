package com.joysong.server.dm.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.dm.dto.CreateDmConversationRequest
import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.dto.DmMessageResponse
import com.joysong.server.dm.dto.SendDmMessageRequest
import com.joysong.server.dm.service.DmService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/dm")
class DmController(
    private val dmService: DmService
) {

    /**
     * 获取当前用户的会话列表
     */
    @GetMapping("/conversations")
    fun getConversations(
        authentication: Authentication
    ): BaseResponse<List<DmConversationResponse>> {
        val userId = authentication.principal as String
        val conversations = dmService.getConversations(userId)
        return BaseResponse.success(conversations)
    }

    /**
     * 创建或获取会话
     */
    @PostMapping("/conversations")
    fun createConversation(
        authentication: Authentication,
        @RequestBody request: CreateDmConversationRequest
    ): BaseResponse<DmConversationResponse> {
        val userId = authentication.principal as String
        val conversation = dmService.getOrCreateConversation(userId, request.targetId)
        return BaseResponse.success(conversation)
    }

    /**
     * 获取会话的消息历史
     */
    @GetMapping("/conversations/{id}/messages")
    fun getMessages(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestParam(required = false, defaultValue = "30") limit: Int,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        before: LocalDateTime?
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val safeLimit = limit.coerceIn(1, 100)
            val messages = dmService.getMessages(id, userId, safeLimit, before)
            BaseResponse.success(messages)
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权访问")
        }
    }

    /**
     * 发送消息
     */
    @PostMapping("/conversations/{id}/messages")
    fun sendMessage(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: SendDmMessageRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val message = dmService.sendMessage(id, userId, request.content, request.messageType)
            BaseResponse.success(message)
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权发送")
        }
    }

    /**
     * 标记会话已读
     */
    @PutMapping("/conversations/{id}/read")
    fun markAsRead(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            dmService.markAsRead(id, userId)
            BaseResponse.success("ok")
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权操作")
        }
    }

    /**
     * 删除消息
     */
    @DeleteMapping("/messages/{id}")
    fun deleteMessage(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            dmService.deleteMessage(id, userId)
            BaseResponse.success("ok")
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权删除")
        }
    }
}
