package com.joysong.server.chat.controller

import com.joysong.server.chat.dto.ChatMessageResponse
import com.joysong.server.chat.dto.ChatSessionResponse
import com.joysong.server.chat.dto.ChatTurnResponse
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.service.ChatService
import com.joysong.server.common.BaseResponse
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    /**
     * 创建新的聊天会话
     */
    @PostMapping("/sessions")
    fun createSession(
        authentication: Authentication,
        @RequestBody request: CreateSessionRequest
    ): BaseResponse<ChatSessionResponse> {
        val userId = authentication.principal as String
        val session = chatService.createSession(userId, request)
        return BaseResponse.success(session.toResponse())
    }

    /**
     * 获取当前用户的会话列表，可按 persona 筛选
     */
    @GetMapping("/sessions")
    fun getSessions(
        authentication: Authentication,
        @RequestParam(required = false) persona: String?
    ): BaseResponse<List<ChatSessionResponse>> {
        val userId = authentication.principal as String
        val sessions = chatService.getSessions(userId, persona)
        return BaseResponse.success(sessions.map { it.toResponse() })
    }

    /**
     * 向指定会话发送消息
     */
    @PostMapping("/sessions/{id}/messages")
    fun sendMessage(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: SendMessageRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val turn = chatService.sendMessage(id, userId, request)
            BaseResponse.success(ChatTurnResponse(turn.message.toResponse(), turn.catalogReport, turn.catalogItems, turn.intent, turn.queryTarget, turn.nextAction))
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 404, message = e.message ?: "会话不存在")
        } catch (e: IllegalStateException) {
            val message = e.message ?: "AI_PROVIDER_UNAVAILABLE"
            BaseResponse<Any>(code = if (message == "AI_PROVIDER_UNAVAILABLE") 503 else 500, message = message)
        }
    }

    /**
     * 获取指定会话的历史消息
     */
    @GetMapping("/sessions/{id}/messages")
    fun getMessages(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        before: LocalDateTime?
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val messages = chatService.getMessages(id, userId, limit, before)
            BaseResponse.success(messages.map { it.toResponse() })
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 404, message = e.message ?: "会话不存在")
        }
    }

    /** Reserved for future gateways. Disabled while OPENAI_STREAM_ENABLED=false. */
    @PostMapping("/sessions/{id}/messages/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamMessage(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: SendMessageRequest
    ): SseEmitter {
        val userId = authentication.principal as String
        val emitter = SseEmitter(120_000L)
        CompletableFuture.runAsync {
            try {
                val turn = chatService.sendMessageStreaming(id, userId, request) { delta ->
                    emitter.send(SseEmitter.event().name("delta").data(mapOf("content" to delta)))
                }
                emitter.send(SseEmitter.event().name("done").data(ChatTurnResponse(turn.message.toResponse(), turn.catalogReport, turn.catalogItems, turn.intent, turn.queryTarget, turn.nextAction)))
                emitter.complete()
            } catch (error: Exception) {
                runCatching { emitter.send(SseEmitter.event().name("error").data(mapOf("message" to (error.message ?: "Stream failed")))) }
                emitter.complete()
            }
        }
        return emitter
    }

    @DeleteMapping("/sessions/{id}")
    fun deleteSession(authentication: Authentication, @PathVariable id: String): BaseResponse<*> = try {
        chatService.deleteSession(id, authentication.principal as String)
        BaseResponse.success("ok")
    } catch (e: IllegalArgumentException) {
        BaseResponse<Any>(code = 403, message = e.message ?: "无权删除")
    }

    @DeleteMapping("/sessions")
    fun clearSessions(
        authentication: Authentication,
        @RequestParam(defaultValue = "CONSULTANT") persona: String
    ): BaseResponse<*> {
        chatService.clearSessions(authentication.principal as String, persona)
        return BaseResponse.success("ok")
    }

    @DeleteMapping("/sessions/{id}/messages")
    fun clearMessages(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            chatService.clearMessages(id, userId)
            BaseResponse.success("ok")
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权清空")
        }
    }

    /**
     * 删除单条消息
     */
    @DeleteMapping("/messages/{id}")
    fun deleteMessage(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            chatService.deleteMessage(id, userId)
            BaseResponse.success("ok")
        } catch (e: IllegalArgumentException) {
            BaseResponse<Any>(code = 403, message = e.message ?: "无权删除")
        }
    }

    // ===== Entity -> Response 转换扩展函数 =====

    private fun ChatSessionEntity.toResponse(): ChatSessionResponse {
        return ChatSessionResponse(
            id = id,
            persona = persona,
            contextType = contextType,
            contextId = contextId,
            title = title,
            lastMessage = chatService.getLastMessage(id),
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString()
        )
    }

    private fun ChatMessageEntity.toResponse(): ChatMessageResponse {
        return ChatMessageResponse(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            createdAt = createdAt.toString()
        )
    }
}
