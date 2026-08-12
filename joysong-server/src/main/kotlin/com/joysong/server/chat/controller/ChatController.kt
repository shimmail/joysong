package com.joysong.server.chat.controller

import com.joysong.server.chat.dto.ChatMessageResponse
import com.joysong.server.chat.dto.ChatSessionResponse
import com.joysong.server.chat.dto.ChatTurnResponse
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.service.ChatService
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.common.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.format.annotation.DateTimeFormat
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService,
    private val turnLifecycleService: TurnLifecycleService
) {

    /**
     * 创建新的聊天会话
     */
    @PostMapping("/sessions")
    fun createSession(
        authentication: Authentication,
        @RequestBody request: CreateSessionRequest
    ): BaseResponse<ChatSessionResponse> {
        val userId = authentication.name
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
        val userId = authentication.name
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
    ): ResponseEntity<BaseResponse<ChatTurnResponse>> {
        val turn = chatService.sendMessage(id, authentication.name, request)
        return ResponseEntity.ok(
            BaseResponse.success(
                ChatTurnResponse(
                    message = turn.message.toResponse(turn.catalogItems),
                    catalogReport = turn.catalogReport,
                    catalogItems = turn.catalogItems,
                    intent = turn.intent,
                    queryTarget = turn.queryTarget,
                    nextAction = turn.nextAction,
                    traceId = turn.traceId
                )
            )
        )
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
    ): BaseResponse<List<ChatMessageResponse>> = BaseResponse.success(
        chatService.getMessages(id, authentication.name, limit, before).map {
            it.toResponse(turnLifecycleService.catalogItemsForMessage(it))
        }
    )

    @PostMapping("/sessions/{id}/messages/stream")
    fun streamMessage(): ResponseEntity<BaseResponse<Nothing>> =
        throw AgentChatException("AGENT_STREAMING_DISABLED")

    @DeleteMapping("/sessions/{id}")
    fun deleteSession(authentication: Authentication, @PathVariable id: String): BaseResponse<String> {
        chatService.deleteSession(id, authentication.name)
        return BaseResponse.success("ok")
    }

    @DeleteMapping("/sessions")
    fun clearSessions(
        authentication: Authentication,
        @RequestParam(defaultValue = "CONSULTANT") persona: String
    ): BaseResponse<*> {
        chatService.clearSessions(authentication.name, persona)
        return BaseResponse.success("ok")
    }

    @DeleteMapping("/sessions/{id}/messages")
    fun clearMessages(authentication: Authentication, @PathVariable id: String): BaseResponse<String> {
        chatService.clearMessages(id, authentication.name)
        return BaseResponse.success("ok")
    }

    /**
     * 删除单条消息
     */
    @DeleteMapping("/messages/{id}")
    fun deleteMessage(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<String> {
        chatService.deleteMessage(id, authentication.name)
        return BaseResponse.success("ok")
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

    private fun ChatMessageEntity.toResponse(catalogItems: List<com.joysong.server.agent.dto.AgentCatalogItemResponse> = emptyList()): ChatMessageResponse {
        return ChatMessageResponse(
            id = id,
            sessionId = sessionId,
            role = role,
            content = content,
            createdAt = createdAt.toString(),
            catalogItems = catalogItems
        )
    }
}
