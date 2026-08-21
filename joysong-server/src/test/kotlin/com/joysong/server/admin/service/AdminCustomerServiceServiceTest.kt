package com.joysong.server.admin.service

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

class AdminCustomerServiceServiceTest {
    private val conversationRepository = mockk<DmConversationRepository>()
    private val messageRepository = mockk<DmMessageRepository>()
    private val userRepository = mockk<UserRepository>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val service = AdminCustomerServiceService(
        conversationRepository,
        messageRepository,
        userRepository,
        notificationService
    )

    @Test
    fun `admin customer service inbox queries only direct sentinel conversations`() {
        every {
            conversationRepository.findByConversationTypeAndParticipantOrderByLastMessageAtDesc(
                DmConversationEntity.DIRECT,
                "CS_ADMIN"
            )
        } returns emptyList()

        assertEquals(emptyList<CsConversationResponse>(), service.getAllConversations())
    }

    @Test
    fun `admin customer service history rejects order conversation injection`() {
        stubMissingDirectSentinel("order-chat-1")

        assertThrows<IllegalArgumentException> {
            service.getConversationMessages("order-chat-1")
        }
        verify(exactly = 0) {
            messageRepository.findByConversationIdOrderByCreatedAtDesc(any(), any())
        }
    }

    @Test
    fun `admin customer service send rejects direct conversation without sentinel`() {
        stubMissingDirectSentinel("direct-user-chat")

        assertThrows<IllegalArgumentException> {
            service.sendMessage("direct-user-chat", "旁路回复")
        }
        verify(exactly = 0) { messageRepository.save(any()) }
    }

    @Test
    fun `admin customer service mark read rejects order conversation injection`() {
        stubMissingDirectSentinel("order-chat-1")

        assertThrows<IllegalArgumentException> {
            service.markConversationAsRead("order-chat-1")
        }
        verify(exactly = 0) { messageRepository.markAsRead(any(), any()) }
    }

    private fun stubMissingDirectSentinel(conversationId: String) {
        every {
            conversationRepository.findByIdAndConversationTypeAndParticipant(
                conversationId,
                DmConversationEntity.DIRECT,
                "CS_ADMIN"
            )
        } returns null
    }
}
