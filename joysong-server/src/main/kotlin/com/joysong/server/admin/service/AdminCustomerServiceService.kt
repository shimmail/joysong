package com.joysong.server.admin.service

import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.entity.DmMessageEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.dm.repository.DmMessageRepository
import com.joysong.server.notification.service.NotificationService
import com.joysong.server.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

// ─── DTO ──────────────────────────────────────────────────────────────────────

data class CsConversationResponse(
    val id: String,
    val userId: String,
    val userNickname: String,
    val userAvatar: String,
    val lastMessage: String?,
    val lastMessageAt: String?,
    val unreadCount: Int
)

data class CsMessageResponse(
    val id: String,
    val senderId: String,
    val senderName: String,
    val content: String,
    val messageType: String,
    val isRead: Boolean,
    val createdAt: String
)

data class CsSendRequest(val content: String)

// ─── Service ──────────────────────────────────────────────────────────────────

private const val CS_ADMIN = "CS_ADMIN"

@Service
class AdminCustomerServiceService(
    private val conversationRepository: DmConversationRepository,
    private val messageRepository: DmMessageRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    /**
     * 获取所有客服会话列表（管理员视角），按最新消息时间降序排列。
     * 可选 keyword 按用户昵称模糊过滤。
     */
    fun getAllConversations(keyword: String? = null): List<CsConversationResponse> {
        val conversations = conversationRepository
            .findByConversationTypeAndParticipantOrderByLastMessageAtDesc(
                DmConversationEntity.DIRECT,
                CS_ADMIN
            )

        return conversations.mapNotNull { conv ->
            // 确定"用户方"：非 CS_ADMIN 的参与者
            val userId = when {
                conv.userAId == CS_ADMIN -> conv.userBId
                conv.userBId == CS_ADMIN -> conv.userAId
                else -> return@mapNotNull null // 两个都是真实用户的会话，跳过
            }

            val user = userRepository.findByIdAnyState(userId)
            val nickname = user?.nickname ?: "未知用户"

            // keyword 过滤（按昵称模糊匹配）
            if (!keyword.isNullOrBlank()) {
                if (!nickname.contains(keyword.trim(), ignoreCase = true)) {
                    return@mapNotNull null
                }
            }

            // 客服侧（CS_ADMIN）的未读数：管理员需要看到的是用户发来的未读消息数
            val unreadCount = if (conv.userAId == CS_ADMIN) conv.userAUnread else conv.userBUnread

            CsConversationResponse(
                id = conv.id,
                userId = userId,
                userNickname = nickname,
                userAvatar = user?.avatar ?: "",
                lastMessage = conv.lastMessage,
                lastMessageAt = conv.lastMessageAt?.toString(),
                unreadCount = unreadCount
            )
        }
    }

    /**
     * 获取指定会话的消息历史，按创建时间降序，取 [limit] 条。
     */
    fun getConversationMessages(conversationId: String, limit: Int = 50): List<CsMessageResponse> {
        require(conversationId.isNotBlank()) { "会话 ID 不能为空" }
        requireCustomerServiceConversation(conversationId)

        val safeLimit = limit.coerceIn(1, 100)
        val pageable = PageRequest.of(0, safeLimit)
        val messages = messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable)

        return messages.map { msg ->
            val senderName = if (msg.senderId == CS_ADMIN) {
                "平台客服"
            } else {
                userRepository.findByIdAnyState(msg.senderId)?.nickname ?: "未知用户"
            }

            CsMessageResponse(
                id = msg.id,
                senderId = msg.senderId,
                senderName = senderName,
                content = msg.content,
                messageType = msg.messageType,
                isRead = msg.isRead,
                createdAt = msg.createdAt.toString()
            )
        }
    }

    /**
     * 以平台客服身份向指定会话发送消息，并给用户推送通知。
     */
    @Transactional
    fun sendMessage(conversationId: String, content: String): CsMessageResponse {
        require(content.isNotBlank()) { "消息内容不能为空" }
        require(content.length <= 5000) { "消息内容不能超过 5000 字符" }

        val conversation = requireCustomerServiceConversation(conversationId)

        // 创建消息
        val message = DmMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = CS_ADMIN,
            content = content,
            messageType = "TEXT",
            isRead = false,
            createdAt = LocalDateTime.now()
        )
        messageRepository.save(message)

        // 更新会话摘要
        conversation.lastMessage = content
        conversation.lastMessageAt = message.createdAt

        // 确定用户方并增加其未读计数（客服发消息，用户方未读 +1）
        val userId = if (conversation.userAId == CS_ADMIN) conversation.userBId else conversation.userAId
        if (userId == conversation.userAId) {
            conversation.userAUnread += 1
        } else {
            conversation.userBUnread += 1
        }
        conversationRepository.save(conversation)

        // 给用户发通知
        val truncatedContent = if (content.length > 50) content.take(50) + "…" else content
        notificationService.createNotification(
            userId = userId,
            type = "DM_NEW",
            title = "客服回复",
            content = truncatedContent,
            targetType = "dm_conversation",
            targetId = conversationId
        )

        return CsMessageResponse(
            id = message.id,
            senderId = message.senderId,
            senderName = "平台客服",
            content = message.content,
            messageType = message.messageType,
            isRead = message.isRead,
            createdAt = message.createdAt.toString()
        )
    }

    /**
     * 将客服侧（CS_ADMIN）收到的消息标记为已读，并将对应未读计数归零。
     */
    @Transactional
    fun markConversationAsRead(conversationId: String) {
        val conversation = requireCustomerServiceConversation(conversationId)

        // 批量将用户发给 CS_ADMIN 的消息（sender_id != CS_ADMIN）标记为已读
        messageRepository.markAsRead(conversationId, CS_ADMIN)

        // 将 CS_ADMIN 一侧的未读计数归零
        if (conversation.userAId == CS_ADMIN) {
            conversation.userAUnread = 0
        } else {
            conversation.userBUnread = 0
        }
        conversationRepository.save(conversation)
    }

    private fun requireCustomerServiceConversation(conversationId: String): DmConversationEntity =
        conversationRepository.findByIdAndConversationTypeAndParticipant(
            conversationId,
            DmConversationEntity.DIRECT,
            CS_ADMIN
        ) ?: throw IllegalArgumentException("会话不存在")
}
