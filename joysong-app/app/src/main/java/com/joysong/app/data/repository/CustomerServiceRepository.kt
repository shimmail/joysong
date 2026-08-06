package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiException
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.CsConversationDto
import com.joysong.app.data.remote.dto.CsMessageDto
import com.joysong.app.data.remote.dto.SendCsMessageRequest
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomerServiceRepository @Inject constructor(
    private val apiService: ApiService
) {

    /** Mock mode toggle — true = use local mock data, false = call real API */
    var useMock: Boolean = false

    /** Mock 客服 agent ID，isUser 判断依赖此变量 */
    private val mockAgentId = "CS_ADMIN"

    // region Mock data

    private val mockConversation = CsConversationDto(
        id = "cs-conv-001",
        userAId = "current-user",
        userBId = mockAgentId,
        lastMessage = "您好，请问有什么可以帮您？",
        lastMessageAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        unreadCount = 1,
        createdAt = LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        updatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    )

    private val mockHistoryMessages = listOf(
        CsMessageDto(
            id = "cs-msg-001",
            senderId = mockAgentId,
            senderName = "客服小王",
            content = "您好，欢迎咨询！请问有什么可以帮您？",
            messageType = "TEXT",
            isRead = true,
            createdAt = LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ),
        CsMessageDto(
            id = "cs-msg-002",
            senderId = "current-user",
            senderName = "我",
            content = "我想咨询一下关于热玛吉项目的价格",
            messageType = "TEXT",
            isRead = true,
            createdAt = LocalDateTime.now().minusMinutes(25).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ),
        CsMessageDto(
            id = "cs-msg-003",
            senderId = mockAgentId,
            senderName = "客服小王",
            content = "好的，热玛吉项目的价格根据不同部位和发数有所区别，您可以在我们的发现页查看具体机构和报价哦~",
            messageType = "TEXT",
            isRead = true,
            createdAt = LocalDateTime.now().minusMinutes(20).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ),
        CsMessageDto(
            id = "cs-msg-004",
            senderId = mockAgentId,
            senderName = "客服小王",
            content = "请问还有什么可以帮您的吗？",
            messageType = "TEXT",
            isRead = false,
            createdAt = LocalDateTime.now().minusMinutes(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    )

    // Auto-reply templates for mock
    private val autoReplies = listOf(
        "好的，我帮您查看一下~",
        "收到您的问题，请稍等片刻~",
        "感谢您的咨询！我们会尽快处理~",
        "好的，已为您记录，稍后会有专人跟进~",
        "没问题，这个我来帮您确认一下~"
    )

    private var autoReplyIndex = 0

    // endregion

    suspend fun getOrCreateConversation(): Result<CsConversationDto> {
        if (useMock) {
            delay(300)
            return Result.success(mockConversation)
        }
        return try {
            val response = apiService.createCsConversation()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getConversations(): Result<List<CsConversationDto>> {
        if (useMock) {
            delay(200)
            return Result.success(listOf(mockConversation))
        }
        return try {
            val response = apiService.getCsConversations()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(conversationId: String, content: String, messageType: String = "TEXT"): Result<CsMessageDto> {
        if (useMock) {
            delay(200)
            // Return user message as confirmation
            val userMsg = CsMessageDto(
                id = UUID.randomUUID().toString(),
                senderId = "current-user",
                senderName = "我",
                content = content,
                messageType = "TEXT",
                isRead = true,
                createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
            return Result.success(userMsg)
        }
        return try {
            val response = apiService.sendCsMessage(conversationId, SendCsMessageRequest(content, messageType))
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generate a mock auto-reply message from CS agent.
     * Only works in mock mode.
     */
    suspend fun mockAutoReply(): CsMessageDto {
        delay(1500) // simulate network delay
        val reply = autoReplies[autoReplyIndex % autoReplies.size]
        autoReplyIndex++
        return CsMessageDto(
            id = UUID.randomUUID().toString(),
            senderId = mockAgentId,
            senderName = "客服小王",
            content = reply,
            messageType = "TEXT",
            isRead = false,
            createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        )
    }

    /**
     * 获取客服常见问题列表（纯 Mock，无后端接口）
     */
    fun getCommonQuestions(): List<String> {
        return listOf(
            "如何预约项目？",
            "预约后可以取消吗？",
            "如何申请退款？",
            "面诊金是什么？",
            "如何查看我的订单？"
        )
    }

    suspend fun getMessages(conversationId: String, limit: Int = 50): Result<List<CsMessageDto>> {
        if (useMock) {
            delay(300)
            return Result.success(mockHistoryMessages)
        }
        return try {
            val response = apiService.getCsMessages(conversationId, limit)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markAsRead(conversationId: String): Result<Unit> {
        if (useMock) {
            delay(100)
            return Result.success(Unit)
        }
        return try {
            val response = apiService.markCsConversationRead(conversationId)
            if (response.code == 200) Result.success(Unit)
            else Result.failure(ApiException(response.code, response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
