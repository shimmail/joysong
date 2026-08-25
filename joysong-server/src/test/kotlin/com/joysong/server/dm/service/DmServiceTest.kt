package com.joysong.server.dm.service

import com.joysong.server.dm.dto.toResponse
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.entity.DmMessageEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.dm.repository.DmMessageRepository
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class DmServiceTest {
    private val conversationRepository = mockk<DmConversationRepository>()
    private val messageRepository = mockk<DmMessageRepository>()
    private val userRepository = mockk<UserRepository>()
    private val identityAuthorizationService = mockk<IdentityAuthorizationService>()
    private val orderConversationService = mockk<OrderServiceConversationService>()
    private val service = DmService(
        conversationRepository,
        messageRepository,
        userRepository,
        identityAuthorizationService,
        orderConversationService
    )

    @Test
    fun `inbox keeps direct and readable order conversations while filtering unauthorized order and cs`() {
        val direct = directConversation(id = "direct-1", userAId = "other-1", userBId = "user-1")
        val readableOrder = orderConversation(id = "order-chat-1", orderId = "order-1")
        val unauthorizedOrder = orderConversation(id = "order-chat-2", orderId = "order-2")
        val cs = directConversation(id = "cs-1", userAId = "CS_ADMIN")
        every {
            conversationRepository.findByParticipantOrderByLastMessageAtDesc("user-1")
        } returns listOf(direct, readableOrder, unauthorizedOrder, cs)
        every {
            orderConversationService.responseIfReadable(readableOrder, "user-1")
        } returns readableOrder.toResponse(canHide = true, serviceMessagingEnabled = true)
        every {
            orderConversationService.responseIfReadable(unauthorizedOrder, "user-1")
        } returns null
        every { messageRepository.existsByConversationIdAndSenderId(any(), any()) } returns false
        every { identityAuthorizationService.hasActiveProfessionalRole(any()) } returns false

        val result = service.getConversations("user-1")

        assertEquals(listOf("direct-1", "order-chat-1"), result.map { it.id })
        assertEquals(DmConversationEntity.ORDER_SERVICE, result[1].conversationType)
        assertEquals("order-1", result[1].orderId)
        assertFalse(result[1].firstMessageLimitApplies)
        assertFalse(result[1].waitingForReply)
        assertTrue(result[1].canHide)
        assertTrue(result[1].serviceMessagingEnabled)
        verify(exactly = 1) {
            orderConversationService.responseIfReadable(readableOrder, "user-1")
        }
        verify(exactly = 1) {
            orderConversationService.responseIfReadable(unauthorizedOrder, "user-1")
        }
    }

    @Test
    fun `order history and mark read both require order read authorization`() {
        val conversation = orderConversation()
        val message = DmMessageEntity(
            id = "message-1",
            conversationId = conversation.id,
            senderId = "consultant-1",
            content = "行程安排"
        )
        every { conversationRepository.findById(conversation.id) } returns Optional.of(conversation)
        every { orderConversationService.requireReadAccess(conversation, "user-1") } just Runs
        every {
            messageRepository.findByConversationIdOrderByCreatedAtDesc(
                conversation.id,
                any<Pageable>()
            )
        } returns listOf(message)
        every { messageRepository.markAsRead(conversation.id, "user-1") } returns 1
        every { conversationRepository.save(any()) } answers { firstArg() }

        val history = service.getMessages(conversation.id, "user-1")
        service.markAsRead(conversation.id, "user-1")

        assertEquals(listOf("message-1"), history.map { it.id })
        assertEquals(0, conversation.userBUnread)
        verify(exactly = 2) { orderConversationService.requireReadAccess(conversation, "user-1") }
    }

    @Test
    fun `active order send bypasses direct first message throttle`() {
        val conversation = orderConversation()
        every { conversationRepository.findByIdForUpdate(conversation.id) } returns conversation
        every { orderConversationService.requireSendAccess(conversation, "user-1") } just Runs
        every { identityAuthorizationService.hasActiveProfessionalRole("consultant-1") } returns false
        every {
            messageRepository.existsByConversationIdAndSenderId(conversation.id, "user-1")
        } returns true
        every {
            messageRepository.existsByConversationIdAndSenderId(conversation.id, "consultant-1")
        } returns false
        every { messageRepository.save(any()) } answers { firstArg() }
        every { conversationRepository.save(any()) } answers { firstArg() }

        val result = service.sendMessage(conversation.id, "user-1", "第二条履约消息")

        assertEquals("第二条履约消息", result.content)
        verify(exactly = 0) { identityAuthorizationService.hasActiveProfessionalRole(any()) }
    }

    @Test
    fun `direct send keeps message and unread state in the inbox`() {
        val conversation = directConversation(
            userAId = "user-1",
            userBId = "consultant-1"
        )
        every { conversationRepository.findByIdForUpdate(conversation.id) } returns conversation
        every { identityAuthorizationService.hasActiveProfessionalRole("consultant-1") } returns true
        every { messageRepository.save(any()) } answers { firstArg() }
        every { conversationRepository.save(any()) } answers { firstArg() }

        val result = service.sendMessage(conversation.id, "user-1", "普通私信")

        assertEquals("普通私信", result.content)
        assertEquals(1, conversation.userBUnread)
    }

    @Test
    fun `refund state send denial happens before message persistence`() {
        val conversation = orderConversation()
        every { conversationRepository.findByIdForUpdate(conversation.id) } returns conversation
        every {
            orderConversationService.requireSendAccess(conversation, "user-1")
        } throws IllegalArgumentException("ORDER_SERVICE_NOT_ACTIVE")

        val error = assertThrows<IllegalArgumentException> {
            service.sendMessage(conversation.id, "user-1", "退款期间消息")
        }

        assertEquals("ORDER_SERVICE_NOT_ACTIVE", error.message)
        verify(exactly = 0) { messageRepository.save(any()) }
    }

    @Test
    fun `order service messages are never deletable even by their sender`() {
        val conversation = orderConversation()
        val message = DmMessageEntity(
            id = "message-1",
            conversationId = conversation.id,
            senderId = "user-1",
            content = "审计记录"
        )
        every { messageRepository.findById(message.id) } returns Optional.of(message)
        every { conversationRepository.findById(conversation.id) } returns Optional.of(conversation)
        every { orderConversationService.requireReadAccess(conversation, "user-1") } just Runs

        val error = assertThrows<IllegalArgumentException> {
            service.deleteMessage(message.id, "user-1")
        }

        assertEquals("ORDER_SERVICE_MESSAGES_NOT_DELETABLE", error.message)
        verify(exactly = 1) { orderConversationService.requireReadAccess(conversation, "user-1") }
        verify(exactly = 0) { messageRepository.delete(any()) }
    }

    @Test
    fun `order message delete rejects an unrelated user before revealing retention policy`() {
        val conversation = orderConversation()
        val message = DmMessageEntity(
            id = "message-1",
            conversationId = conversation.id,
            senderId = "user-1",
            content = "审计记录"
        )
        every { messageRepository.findById(message.id) } returns Optional.of(message)
        every { conversationRepository.findById(conversation.id) } returns Optional.of(conversation)
        every {
            orderConversationService.requireReadAccess(conversation, "unrelated-user")
        } throws IllegalArgumentException("ORDER_SERVICE_ACCESS_DENIED")

        val error = assertThrows<IllegalArgumentException> {
            service.deleteMessage(message.id, "unrelated-user")
        }

        assertEquals("ORDER_SERVICE_ACCESS_DENIED", error.message)
        verify(exactly = 0) { messageRepository.delete(any()) }
    }

    @Test
    fun `existing direct conversation remains usable for professional to ordinary pair`() {
        val existing = directConversation()
        every { userRepository.findById("user-2") } returns Optional.of(mockk<UserEntity>())
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "professional-1",
                "user-2"
            )
        } returns existing
        every { messageRepository.existsByConversationIdAndSenderId(any(), any()) } returns false
        every { identityAuthorizationService.hasActiveProfessionalRole("user-2") } returns false

        val result = service.getOrCreateConversation("professional-1", "user-2")

        assertEquals(existing.id, result.id)
        assertTrue(result.serviceMessagingEnabled)
        verify(exactly = 0) { conversationRepository.insertDirectIfAbsent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `active consultant cannot create a new direct conversation with ordinary user`() {
        stubTarget("user-2")
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "consultant-1",
                "user-2"
            )
        } returns null
        every {
            identityAuthorizationService.hasActiveRole("consultant-1", "CONSULTANT")
        } returns true
        every { identityAuthorizationService.hasActiveProfessionalRole("user-2") } returns false

        val error = assertThrows<IllegalArgumentException> {
            service.getOrCreateConversation("consultant-1", "user-2")
        }

        assertEquals("DIRECT_OUTREACH_NOT_ALLOWED", error.message)
        verify(exactly = 0) { conversationRepository.insertDirectIfAbsent(any(), any(), any(), any(), any()) }
    }

    @ParameterizedTest
    @CsvSource(
        "false,true",
        "false,false",
        "true,true"
    )
    fun `ordinary and consultant allowed direct creation directions stay available`(
        actorConsultant: Boolean,
        targetProfessional: Boolean
    ) {
        stubTarget("target-1")
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "actor-1",
                "target-1"
            )
        } returns null
        every {
            identityAuthorizationService.hasActiveRole("actor-1", "CONSULTANT")
        } returns actorConsultant
        every { identityAuthorizationService.hasActiveProfessionalRole("target-1") } returns targetProfessional
        every { messageRepository.existsByConversationIdAndSenderId(any(), any()) } returns false
        every {
            conversationRepository.insertDirectIfAbsent(
                any(),
                "actor-1",
                "target-1",
                any(),
                any()
            )
        } returns 1
        every {
            conversationRepository.findDirectByPairForUpdate("actor-1", "target-1")
        } returns directConversation(userAId = "actor-1", userBId = "target-1")

        val result = service.getOrCreateConversation("actor-1", "target-1")

        assertEquals(DmConversationEntity.DIRECT, result.conversationType)
        assertEquals(null, result.orderId)
    }

    @Test
    fun `doctor-only professional may create a new direct conversation with ordinary user`() {
        stubTarget("user-2")
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "doctor-1",
                "user-2"
            )
        } returns null
        every { identityAuthorizationService.hasActiveProfessionalRole("doctor-1") } returns true
        every { identityAuthorizationService.hasActiveRole("doctor-1", "CONSULTANT") } returns false
        every { identityAuthorizationService.hasActiveProfessionalRole("user-2") } returns false
        every { messageRepository.existsByConversationIdAndSenderId(any(), any()) } returns false
        every {
            conversationRepository.insertDirectIfAbsent(any(), "doctor-1", "user-2", any(), any())
        } returns 1
        every {
            conversationRepository.findDirectByPairForUpdate("doctor-1", "user-2")
        } returns directConversation(userAId = "doctor-1", userBId = "user-2")

        val result = service.getOrCreateConversation("doctor-1", "user-2")

        assertEquals(DmConversationEntity.DIRECT, result.conversationType)
    }

    @Test
    fun `one direct and two order conversations coexist for the same participants`() {
        val created = mutableListOf<DmConversationEntity>()
        val orderRepository = mockk<OrderRepository>()
        val scopedService = OrderServiceConversationService(orderRepository, conversationRepository)
        stubTarget("consultant-1")
        every { identityAuthorizationService.hasActiveRole("user-1", "CONSULTANT") } returns false
        every { identityAuthorizationService.hasActiveProfessionalRole("consultant-1") } returns true
        every { messageRepository.existsByConversationIdAndSenderId(any(), any()) } returns false
        every {
            conversationRepository.findByConversationTypeAndUserAIdAndUserBId(
                DmConversationEntity.DIRECT,
                "consultant-1",
                "user-1"
            )
        } returns null
        every {
            conversationRepository.insertDirectIfAbsent(any(), "consultant-1", "user-1", any(), any())
        } returns 1
        every {
            conversationRepository.findDirectByPairForUpdate("consultant-1", "user-1")
        } answers {
            directConversation(userAId = "consultant-1", userBId = "user-1").also(created::add)
        }
        every { conversationRepository.saveAndFlush(any()) } answers {
            firstArg<DmConversationEntity>().also(created::add)
        }
        every { orderRepository.findByIdForUpdate(any()) } answers { serviceOrder(firstArg()) }
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                any()
            )
        } returns null

        service.getOrCreateConversation("user-1", "consultant-1")
        scopedService.getOrCreate("order-1", "user-1")
        scopedService.getOrCreate("order-2", "user-1")

        assertEquals(3, created.size)
        assertEquals(1, created.count { it.conversationType == DmConversationEntity.DIRECT })
        assertEquals(
            setOf("order-1", "order-2"),
            created.filter { it.conversationType == DmConversationEntity.ORDER_SERVICE }
                .mapNotNull { it.orderId }
                .toSet()
        )
    }

    private fun stubTarget(userId: String) {
        every { userRepository.findById(userId) } returns Optional.of(mockk<UserEntity>())
    }

    private fun directConversation(
        id: String = "direct-1",
        userAId: String = "professional-1",
        userBId: String = "user-2"
    ) = DmConversationEntity(
        id = id,
        conversationType = DmConversationEntity.DIRECT,
        userAId = userAId,
        userBId = userBId
    )

    private fun orderConversation(
        id: String = "order-chat-1",
        orderId: String = "order-1"
    ) = DmConversationEntity(
        id = id,
        conversationType = DmConversationEntity.ORDER_SERVICE,
        orderId = orderId,
        userAId = "consultant-1",
        userBId = "user-1",
        userBUnread = 2
    )

    private fun serviceOrder(id: String) = OrderEntity(
        id = id,
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        status = OrderStatusEnum.SERVICE_ACTIVE.value,
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        consultantId = "consultant-1",
        serviceActivatedAt = LocalDateTime.of(2026, 8, 22, 9, 0)
    )
}
