package com.joysong.server.cs.service

import com.joysong.server.cs.dto.CsConversationResponse
import com.joysong.server.cs.dto.CsMessageResponse
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

private const val CS_ADMIN = "CS_ADMIN"
private const val MESSAGE_TYPE_TEXT = "TEXT"
private const val MESSAGE_TYPE_IMAGE = "IMAGE"
private const val IMAGE_MESSAGE_SUMMARY = "[图片]"

@Service
class CsService(
    private val conversationRepository: DmConversationRepository,
    private val messageRepository: DmMessageRepository,
    private val userRepository: UserRepository,
    private val notificationService: NotificationService
) {

    /**
     * 获取或创建用户与客服（CS_ADMIN）之间的会话。
     * userA = minOf(userId, CS_ADMIN)，userB = maxOf(...)，与 DmService 保持一致。
     */
    @Transactional
    fun getOrCreateConversation(userId: String): CsConversationResponse {
        val userAId = minOf(userId, CS_ADMIN)
        val userBId = maxOf(userId, CS_ADMIN)

        val existing = conversationRepository.findByUserAIdAndUserBId(userAId, userBId)
        if (existing != null) {
            return existing.toCsResponse(userId)
        }

        val conversation = DmConversationEntity(
            id = UUID.randomUUID().toString(),
            userAId = userAId,
            userBId = userBId,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        val saved = try {
            conversationRepository.save(conversation)
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            conversationRepository.findByUserAIdAndUserBId(userAId, userBId)!!
        }
        return saved.toCsResponse(userId)
    }

    /**
     * 获取用户的所有客服会话（通常只有一个），按最新消息时间降序排列。
     */
    fun getConversations(userId: String): List<CsConversationResponse> {
        return conversationRepository
            .findByUserAIdOrUserBIdOrderByLastMessageAtDesc(userId, userId)
            .filter { it.userAId == CS_ADMIN || it.userBId == CS_ADMIN }
            .map { it.toCsResponse(userId) }
    }

    /**
     * 获取指定会话的消息历史，验证会话归属后按 createdAt 升序返回。
     */
    fun getMessages(
        conversationId: String,
        userId: String,
        limit: Int = 50,
        before: LocalDateTime? = null
    ): List<CsMessageResponse> {
        require(conversationId.isNotBlank()) { "会话 ID 不能为空" }

        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { IllegalArgumentException("会话不存在") }

        if (conversation.userAId != userId && conversation.userBId != userId) {
            throw IllegalArgumentException("无权访问该会话")
        }

        val safeLimit = limit.coerceIn(1, 100)
        val pageable = PageRequest.of(0, safeLimit)
        val messages = if (before == null) {
            messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable)
        } else {
            messageRepository.findByConversationIdAndCreatedAtBeforeOrderByCreatedAtDesc(conversationId, before, pageable)
        }
        return messages
            .reversed()
            .map { it.toCsResponse() }
    }

    /**
     * 用户向客服会话发送消息，增加 CS_ADMIN 侧的未读计数，并给客服推送通知。
     */
    @Transactional
    fun sendMessage(
        conversationId: String,
        userId: String,
        content: String,
        messageType: String = MESSAGE_TYPE_TEXT
    ): CsMessageResponse {
        require(content.isNotBlank()) { "消息内容不能为空" }
        require(content.length <= 5000) { "消息内容不能超过 5000 字符" }
        require(messageType in setOf(MESSAGE_TYPE_TEXT, MESSAGE_TYPE_IMAGE)) { "不支持的消息类型" }
        if (messageType == MESSAGE_TYPE_IMAGE) require(content.startsWith("http")) { "图片地址无效" }

        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { IllegalArgumentException("会话不存在") }

        if (conversation.userAId != userId && conversation.userBId != userId) {
            throw IllegalArgumentException("无权发送消息到该会话")
        }

        // 创建消息
        val message = DmMessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            senderId = userId,
            content = content,
            messageType = messageType,
            isRead = false,
            createdAt = LocalDateTime.now()
        )
        messageRepository.save(message)

        // 更新会话摘要
        conversation.lastMessage = if (messageType == MESSAGE_TYPE_IMAGE) IMAGE_MESSAGE_SUMMARY else content
        conversation.lastMessageAt = message.createdAt
        conversation.updatedAt = LocalDateTime.now()

        // 增加 CS_ADMIN 侧的未读计数
        if (conversation.userAId == CS_ADMIN) {
            conversation.userAUnread += 1
        } else {
            conversation.userBUnread += 1
        }
        conversationRepository.save(conversation)

        // 给客服推送通知
        val notificationContent = if (messageType == MESSAGE_TYPE_IMAGE) IMAGE_MESSAGE_SUMMARY else content
        val truncatedContent = if (notificationContent.length > 50) notificationContent.take(50) + "…" else notificationContent
        notificationService.createNotification(
            userId = CS_ADMIN,
            type = "DM_NEW",
            title = "用户咨询",
            content = truncatedContent,
            targetType = "dm_conversation",
            targetId = conversationId
        )

        return message.toCsResponse()
    }

    /**
     * 将客服（CS_ADMIN）发给用户的消息标记为已读，重置用户侧未读计数。
     */
    @Transactional
    fun markAsRead(conversationId: String, userId: String) {
        val conversation = conversationRepository.findById(conversationId)
            .orElseThrow { IllegalArgumentException("会话不存在") }

        if (conversation.userAId != userId && conversation.userBId != userId) {
            throw IllegalArgumentException("无权操作该会话")
        }

        // 批量将 CS_ADMIN 发来的未读消息标记为已读
        messageRepository.markAsRead(conversationId, userId)

        // 将用户侧的未读计数归零
        if (conversation.userAId == userId) {
            conversation.userAUnread = 0
        } else {
            conversation.userBUnread = 0
        }
        conversation.updatedAt = LocalDateTime.now()
        conversationRepository.save(conversation)
    }

    // ─── 内部映射 ────────────────────────────────────────────────────────────

    private fun DmConversationEntity.toCsResponse(userId: String): CsConversationResponse {
        val unreadCount = if (userId == userAId) userAUnread else userBUnread
        return CsConversationResponse(
            id = id,
            userAId = userAId,
            userBId = userBId,
            lastMessage = lastMessage,
            lastMessageAt = lastMessageAt?.toString(),
            unreadCount = unreadCount,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString()
        )
    }

    private fun DmMessageEntity.toCsResponse(): CsMessageResponse {
        val senderName = if (senderId == CS_ADMIN) {
            "平台客服"
        } else {
            userRepository.findByIdIncludingDeleted(senderId)?.nickname ?: "用户"
        }
        return CsMessageResponse(
            id = id,
            senderId = senderId,
            senderName = senderName,
            content = content,
            messageType = messageType,
            isRead = isRead,
            createdAt = createdAt.toString()
        )
    }
}
