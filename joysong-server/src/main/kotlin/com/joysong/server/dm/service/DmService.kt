package com.joysong.server.dm.service

import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.dto.DmMessageResponse
import com.joysong.server.dm.dto.toResponse
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.entity.DmMessageEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.dm.repository.DmMessageRepository
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.notification.service.NotificationService
import com.joysong.server.user.repository.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

private const val MESSAGE_TYPE_TEXT = "TEXT"
private const val MESSAGE_TYPE_IMAGE = "IMAGE"
private const val IMAGE_MESSAGE_SUMMARY = "[图片]"
private const val CONSULTANT_ROLE = "CONSULTANT"

@Service
class DmService(
    private val conversationRepository: DmConversationRepository,
    private val messageRepository: DmMessageRepository,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    private val identityAuthorizationService: IdentityAuthorizationService,
    private val orderServiceConversationService: OrderServiceConversationService
) {

    /**
     * 获取用户的会话列表
     */
    fun getConversations(userId: String): List<DmConversationResponse> {
        return conversationRepository.findByParticipantOrderByLastMessageAtDesc(userId)
            .filter { conversation ->
                when (conversation.conversationType) {
                    DmConversationEntity.DIRECT ->
                        conversation.userAId != "CS_ADMIN" && conversation.userBId != "CS_ADMIN"
                    DmConversationEntity.ORDER_SERVICE ->
                        orderServiceConversationService.canRead(conversation, userId)
                    else -> false
                }
            }
            .map { it.toResponseFor(userId) }
    }

    /**
     * 获取或创建会话
     */
    @Transactional
    fun getOrCreateConversation(userId: String, targetId: String): DmConversationResponse {
        require(targetId.isNotBlank()) { "目标用户不能为空" }
        require(targetId != userId) { "不能与自己创建私信会话" }
        require(userRepository.findById(targetId).isPresent) { "目标用户不存在或已注销" }
        val userAId = minOf(userId, targetId)
        val userBId = maxOf(userId, targetId)

        val existing = conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
            DmConversationEntity.DIRECT,
            userAId,
            userBId
        )
        if (existing != null) {
            return existing.toResponseFor(userId)
        }

        val senderIsConsultant = identityAuthorizationService.hasActiveRole(userId, CONSULTANT_ROLE)
        if (senderIsConsultant) {
            require(identityAuthorizationService.hasActiveProfessionalRole(targetId)) {
                "DIRECT_OUTREACH_NOT_ALLOWED"
            }
        }

        val now = LocalDateTime.now()
        conversationRepository.insertDirectIfAbsent(
            UUID.randomUUID().toString(),
            userAId,
            userBId,
            now,
            now
        )
        val persisted = conversationRepository.findDirectByPairForUpdate(userAId, userBId)
            ?: throw IllegalStateException("DIRECT_CONVERSATION_CREATE_FAILED")
        return persisted.toResponseFor(userId)
    }

    /**
     * 获取会话的消息列表
     */
    fun getMessages(
        conversationId: String,
        userId: String,
        limit: Int = 30,
        before: LocalDateTime? = null
    ): List<DmMessageResponse> {
        require(limit in 1..100) { "limit 必须在 1-100 之间" }
        val conversation = conversationRepository.findById(conversationId).orElse(null)
            ?: throw IllegalArgumentException("会话不存在")

        requireReadAccess(conversation, userId)

        val pageable = PageRequest.of(0, limit)
        val messages = if (before == null) {
            messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable)
        } else {
            messageRepository.findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(conversationId, before, pageable)
        }
        return messages
            .reversed()
            .map { it.toResponse() }
    }

    /**
     * 发送消息
     */
    @Transactional
    fun sendMessage(
        conversationId: String,
        senderId: String,
        content: String,
        messageType: String = MESSAGE_TYPE_TEXT
    ): DmMessageResponse {
        require(content.isNotBlank()) { "消息内容不能为空" }
        require(content.length <= 5000) { "消息内容过长" }
        require(messageType in setOf(MESSAGE_TYPE_TEXT, MESSAGE_TYPE_IMAGE)) { "不支持的消息类型" }
        if (messageType == MESSAGE_TYPE_IMAGE) require(content.startsWith("http")) { "图片地址无效" }

        // 首条消息限制的检查与写入必须在同一个会话行锁内完成，避免并发请求同时通过。
        val conversation = conversationRepository.findByIdForUpdate(conversationId)
            ?: throw IllegalArgumentException("会话不存在")

        when (conversation.conversationType) {
            DmConversationEntity.ORDER_SERVICE ->
                orderServiceConversationService.requireSendAccess(conversation, senderId)
            DmConversationEntity.DIRECT -> {
                requireDirectParticipant(conversation, senderId, "无权发送消息到该会话")
                val directReceiverId = conversation.otherParticipant(senderId)
                if (!identityAuthorizationService.hasActiveProfessionalRole(directReceiverId)) {
                    val receiverHasReplied = messageRepository.existsByConversationIdAndSenderId(
                        conversationId,
                        directReceiverId
                    )
                    val senderAlreadySent = messageRepository.existsByConversationIdAndSenderId(
                        conversationId,
                        senderId
                    )
                    require(receiverHasReplied || !senderAlreadySent) { "请等待对方回复后再发送消息" }
                }
            }
            else -> throw IllegalArgumentException("无权发送消息到该会话")
        }

        val receiverId = conversation.otherParticipant(senderId)

        // 创建消息
        val message = DmMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = senderId,
            content = content,
            messageType = messageType,
            createdAt = LocalDateTime.now()
        )
        messageRepository.save(message)

        // 更新会话的 lastMessage 和 lastMessageAt
        conversation.lastMessage = if (messageType == MESSAGE_TYPE_IMAGE) IMAGE_MESSAGE_SUMMARY else content
        conversation.lastMessageAt = LocalDateTime.now()
        conversation.updatedAt = LocalDateTime.now()

        // 更新对方的 unread 计数
        if (conversation.userAId == senderId) {
            conversation.userBUnread += 1
        } else {
            conversation.userAUnread += 1
        }

        conversationRepository.save(conversation)

        // 给接收方创建通知
        val notificationContent = if (messageType == MESSAGE_TYPE_IMAGE) IMAGE_MESSAGE_SUMMARY else content
        val truncatedContent = if (notificationContent.length > 50) notificationContent.substring(0, 50) else notificationContent
        notificationService.createNotification(
            userId = receiverId,
            type = "DM_NEW",
            title = "新私信",
            content = truncatedContent,
            targetType = "dm_conversation",
            targetId = conversationId
        )

        return message.toResponse()
    }

    /**
     * 标记会话已读
     */
    @Transactional
    fun markAsRead(conversationId: String, userId: String) {
        val conversation = conversationRepository.findById(conversationId).orElse(null)
            ?: throw IllegalArgumentException("会话不存在")

        requireReadAccess(conversation, userId)

        // 批量将对方发来的未读消息标记为已读
        messageRepository.markAsRead(conversationId, userId)

        // 将自己的 unread 计数归零
        if (conversation.userAId == userId) {
            conversation.userAUnread = 0
        } else {
            conversation.userBUnread = 0
        }

        conversation.updatedAt = LocalDateTime.now()
        conversationRepository.save(conversation)
    }

    /**
     * 删除消息（仅发送者可删）
     */
    @Transactional
    fun deleteMessage(messageId: String, userId: String) {
        val message = messageRepository.findById(messageId).orElse(null)
            ?: throw IllegalArgumentException("消息不存在")
        val conversation = conversationRepository.findById(message.conversationId).orElse(null)
            ?: throw IllegalArgumentException("会话不存在")

        if (conversation.conversationType == DmConversationEntity.ORDER_SERVICE) {
            orderServiceConversationService.requireReadAccess(conversation, userId)
            throw IllegalArgumentException("ORDER_SERVICE_MESSAGES_NOT_DELETABLE")
        }

        if (message.senderId != userId) {
            throw IllegalArgumentException("无权删除该消息")
        }

        messageRepository.delete(message)
    }

    private fun DmConversationEntity.otherParticipant(userId: String): String = when (userId) {
        userAId -> userBId
        userBId -> userAId
        else -> throw IllegalArgumentException("无权访问该会话")
    }

    private fun requireReadAccess(conversation: DmConversationEntity, userId: String) {
        when (conversation.conversationType) {
            DmConversationEntity.ORDER_SERVICE ->
                orderServiceConversationService.requireReadAccess(conversation, userId)
            DmConversationEntity.DIRECT ->
                requireDirectParticipant(conversation, userId, "无权访问该会话")
            else -> throw IllegalArgumentException("无权访问该会话")
        }
    }

    private fun requireDirectParticipant(
        conversation: DmConversationEntity,
        userId: String,
        message: String
    ) {
        require(conversation.userAId == userId || conversation.userBId == userId) { message }
    }

    private fun DmConversationEntity.toResponseFor(currentUserId: String): DmConversationResponse {
        if (conversationType != DmConversationEntity.DIRECT) {
            return toResponse()
        }
        val otherUserId = otherParticipant(currentUserId)
        val otherUserHasSent = messageRepository.existsByConversationIdAndSenderId(id, otherUserId)
        val firstMessageLimitApplies =
            !identityAuthorizationService.hasActiveProfessionalRole(otherUserId) && !otherUserHasSent
        val waitingForReply = firstMessageLimitApplies &&
            messageRepository.existsByConversationIdAndSenderId(id, currentUserId)
        return toResponse(
            firstMessageLimitApplies = firstMessageLimitApplies,
            waitingForReply = waitingForReply
        )
    }
}
