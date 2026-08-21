package com.joysong.server.cs.service

import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.dm.repository.DmMessageRepository
import com.joysong.server.notification.service.NotificationService
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CsServiceTest {
    private val conversationRepository = mockk<DmConversationRepository>()
    private val messageRepository = mockk<DmMessageRepository>()
    private val userRepository = mockk<UserRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val service = CsService(
        conversationRepository,
        messageRepository,
        userRepository,
        notificationService
    )

    @Test
    fun `customer service creation upserts and reloads only a direct sentinel conversation`() {
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "CS_ADMIN",
                "user-1"
            )
        } returns null
        every {
            conversationRepository.insertDirectIfAbsent(any(), "CS_ADMIN", "user-1", any(), any())
        } returns 1
        every {
            conversationRepository.findDirectByPairForUpdate("CS_ADMIN", "user-1")
        } returns directCsConversation()

        val result = service.getOrCreateConversation("user-1")

        assertEquals("CS_ADMIN", result.userAId)
        assertEquals("user-1", result.userBId)
        verify(exactly = 1) {
            conversationRepository.insertDirectIfAbsent(any(), "CS_ADMIN", "user-1", any(), any())
        }
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @Test
    fun `customer service duplicate upsert reloads only the locked direct pair`() {
        val existing = directCsConversation()
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "CS_ADMIN",
                "user-1"
            )
        } returns null
        every {
            conversationRepository.insertDirectIfAbsent(any(), "CS_ADMIN", "user-1", any(), any())
        } returns 0
        every {
            conversationRepository.findDirectByPairForUpdate("CS_ADMIN", "user-1")
        } returns existing

        val result = service.getOrCreateConversation("user-1")

        assertEquals(existing.id, result.id)
        verify(exactly = 1) {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "CS_ADMIN",
                "user-1"
            )
        }
        verify(exactly = 1) {
            conversationRepository.findDirectByPairForUpdate("CS_ADMIN", "user-1")
        }
    }

    @Test
    fun `customer service inbox resolves the direct sentinel pair only`() {
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "CS_ADMIN",
                "user-1"
            )
        } returns directCsConversation()

        val result = service.getConversations("user-1")

        assertEquals(listOf("cs-conversation-1"), result.map { it.id })
    }

    @Test
    fun `customer service message history rejects an order conversation id`() {
        every {
            conversationRepository.findByIdAndConversationTypeAndParticipant(
                "order-chat-1",
                DmConversationEntity.DIRECT,
                "CS_ADMIN"
            )
        } returns null

        assertThrows<IllegalArgumentException> {
            service.getMessages("order-chat-1", "user-1")
        }
        verify(exactly = 0) {
            messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())
        }
    }

    @Test
    fun `customer service send rejects a direct conversation without sentinel`() {
        every {
            conversationRepository.findByIdAndConversationTypeAndParticipant(
                "direct-user-chat",
                DmConversationEntity.DIRECT,
                "CS_ADMIN"
            )
        } returns null

        assertThrows<IllegalArgumentException> {
            service.sendMessage("direct-user-chat", "user-1", "旁路消息")
        }
        verify(exactly = 0) { messageRepository.save(any()) }
    }

    @Test
    fun `customer service mark read rejects an order conversation id`() {
        every {
            conversationRepository.findByIdAndConversationTypeAndParticipant(
                "order-chat-1",
                DmConversationEntity.DIRECT,
                "CS_ADMIN"
            )
        } returns null

        assertThrows<IllegalArgumentException> {
            service.markAsRead("order-chat-1", "user-1")
        }
        verify(exactly = 0) { messageRepository.markAsRead(any(), any()) }
    }

    private fun directCsConversation() = DmConversationEntity(
        id = "cs-conversation-1",
        conversationType = DmConversationEntity.DIRECT,
        userAId = "CS_ADMIN",
        userBId = "user-1"
    )
}
