package com.joysong.server.dm.service

import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.order.consultant.ConsultantOrderAccessPolicy
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderContractErrorCode
import com.joysong.server.order.service.OrderContractException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class OrderServiceConversationServiceTest {
    private val orderRepository = mockk<OrderRepository>()
    private val conversationRepository = mockk<DmConversationRepository>()
    private val policy = mockk<ConsultantOrderAccessPolicy>(relaxed = true)
    private val service = OrderServiceConversationService(orderRepository, conversationRepository, policy)

    @Test
    fun `unpaid order cannot create service conversation`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns
            order(status = OrderStatusEnum.PENDING_SERVICE_FEE.value, activatedAt = null)

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @Test
    fun `service active status without activation evidence cannot create conversation`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns
            order(status = OrderStatusEnum.SERVICE_ACTIVE.value, activatedAt = null)

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
    }

    @Test
    fun `legacy flow cannot create service conversation even with active status and timestamp`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns
            order(paymentFlow = "LEGACY_MEDICAL")

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
    }

    @Test
    fun `unrelated user cannot create service conversation`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns order()

        every {
            policy.requireConversationParticipant(any(), "other-user")
        } throws OrderContractException.serviceAccessDenied()

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "other-user")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED, error.errorCode)
    }

    @ParameterizedTest
    @CsvSource(
        "PENDING_SERVICE_FEE, TRAVEL_GROUND_SERVICE_ONLY, false",
        "SERVICE_ACTIVE, LEGACY_MEDICAL, true"
    )
    fun unrelatedUserCannotProbeInactiveOrLegacyOrderWhenCreatingConversation(
        status: String,
        paymentFlow: String,
        hasActivationTimestamp: Boolean
    ) {
        val inaccessibleOrder = order(
            status = status,
            paymentFlow = paymentFlow,
            activatedAt = if (hasActivationTimestamp) LocalDateTime.of(2026, 8, 22, 9, 0) else null
        )
        every { orderRepository.findByIdForUpdate("order-1") } returns inaccessibleOrder
        every {
            policy.requireConversationParticipant(inaccessibleOrder, "other-user")
        } throws OrderContractException.serviceAccessDenied()

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "other-user")
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals(OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED, error.errorCode)
        verify(exactly = 1) {
            policy.requireConversationParticipant(inaccessibleOrder, "other-user")
        }
        verify(exactly = 0) {
            conversationRepository.findByConversationTypeAndOrderId(any(), any())
        }
    }

    @Test
    fun `active order creates one conversation from strict order participants`() {
        val saved = slot<DmConversationEntity>()
        every { orderRepository.findByIdForUpdate("order-1") } returns order()
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        } returns null
        every { conversationRepository.saveAndFlush(capture(saved)) } answers { saved.captured }

        val result = service.getOrCreate("order-1", "user-1")

        assertEquals(DmConversationEntity.ORDER_SERVICE, result.conversationType)
        assertEquals("order-1", result.orderId)
        assertTrue(result.serviceMessagingEnabled)
        assertEquals(setOf("user-1", "consultant-1"), setOf(saved.captured.userAId, saved.captured.userBId))
        assertEquals("order-1", saved.captured.orderId)
        assertEquals(DmConversationEntity.ORDER_SERVICE, saved.captured.conversationType)
    }

    @ParameterizedTest
    @ValueSource(strings = ["COMPLETED", "REFUND_REVIEW", "REFUND_PROCESSING", "REFUNDED"])
    fun `readonly state returns existing conversation but never creates`(status: String) {
        val existing = conversation()
        every { orderRepository.findByIdForUpdate("order-1") } returns order(status = status)
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        } returns existing

        val result = service.getOrCreate("order-1", "consultant-1")

        assertEquals(existing.id, result.id)
        assertFalse(result.serviceMessagingEnabled)
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["user-1", "consultant-1"])
    fun `both active order participants receive messaging enabled capability`(participantId: String) {
        val existing = conversation()
        every { orderRepository.findByIdForUpdate("order-1") } returns order()
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        } returns existing

        val result = service.getOrCreate("order-1", participantId)

        assertTrue(result.serviceMessagingEnabled)
    }

    @ParameterizedTest
    @ValueSource(strings = ["REFUND_REVIEW", "REFUND_PROCESSING", "COMPLETED", "REFUNDED"])
    fun `readonly state without an existing conversation returns read only`(status: String) {
        every { orderRepository.findByIdForUpdate("order-1") } returns order(status = status)
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        } returns null

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_READ_ONLY, error.errorCode)
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING_SERVICE_FEE", "UNKNOWN_STATUS"])
    fun `unpaid and unknown statuses remain not active`(status: String) {
        every { orderRepository.findByIdForUpdate("order-1") } returns order(status = status)

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "user-1"])
    fun `invalid consultant assignment never activates service conversation`(consultantId: String) {
        every { orderRepository.findByIdForUpdate("order-1") } returns order(consultantId = consultantId)

        val error = assertThrows<OrderContractException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
        verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["SERVICE_ACTIVE", "COMPLETED", "REFUND_REVIEW", "REFUND_PROCESSING", "REFUNDED"])
    fun `activated participants may read service history in every readable state`(status: String) {
        every { orderRepository.findById("order-1") } returns Optional.of(order(status = status))

        service.requireReadAccess(conversation(), "user-1")
        service.requireReadAccess(conversation(), "consultant-1")
    }

    @Test
    fun `revoked consultant cannot read or send but consumer still can`() {
        val currentOrder = order()
        every { orderRepository.findById("order-1") } returns Optional.of(currentOrder)
        every { orderRepository.findByIdForUpdate("order-1") } returns currentOrder
        every {
            policy.requireConversationParticipant(currentOrder, "consultant-1")
        } throws OrderContractException.roleRequired()
        every {
            policy.requireConversationParticipant(currentOrder, "user-1")
        } returns Unit

        assertThrows<OrderContractException> {
            service.requireReadAccess(conversation(), "consultant-1")
        }
        assertThrows<OrderContractException> {
            service.requireSendAccess(conversation(), "consultant-1")
        }
        service.requireReadAccess(conversation(), "user-1")
        service.requireSendAccess(conversation(), "user-1")
    }

    @ParameterizedTest
    @CsvSource(
        "SERVICE_ACTIVE, false, true",
        "REFUND_REVIEW, false, false",
        "REFUND_PROCESSING, false, false",
        "COMPLETED, true, false",
        "REFUNDED, true, false"
    )
    fun `inbox projection reads order once and returns status capabilities`(
        status: String,
        expectedCanHide: Boolean,
        expectedMessagingEnabled: Boolean
    ) {
        every { orderRepository.findById("order-1") } returns Optional.of(order(status = status))

        val actual = service.responseIfReadable(conversation(), "user-1")!!

        assertEquals(expectedCanHide, actual.canHide)
        assertEquals(expectedMessagingEnabled, actual.serviceMessagingEnabled)
        verify(exactly = 1) { orderRepository.findById("order-1") }
    }

    @Test
    fun `inbox projection hides conversation from an unrelated user`() {
        val currentOrder = order()
        every { orderRepository.findById("order-1") } returns Optional.of(currentOrder)
        every {
            policy.requireConversationParticipant(currentOrder, "unrelated-user")
        } throws OrderContractException.serviceAccessDenied()

        val actual = service.responseIfReadable(conversation(), "unrelated-user")

        assertNull(actual)
        verify(exactly = 1) { orderRepository.findById("order-1") }
    }

    @Test
    fun `read authorization requires exact conversation participants from the order`() {
        every { orderRepository.findById("order-1") } returns Optional.of(order())
        val mismatched = conversation().copy(userBId = "another-consultant")

        val error = assertThrows<OrderContractException> {
            service.requireReadAccess(mismatched, "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED, error.errorCode)
    }

    @Test
    fun `read authorization rejects status-only legacy and unactivated orders`() {
        listOf(
            order(paymentFlow = "LEGACY_MEDICAL"),
            order(activatedAt = null)
        ).forEach { invalidOrder ->
            every { orderRepository.findById("order-1") } returns Optional.of(invalidOrder)

            val error = assertThrows<OrderContractException> {
                service.requireReadAccess(conversation(), "user-1")
            }

            assertEquals(OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE, error.errorCode)
        }
    }

    @Test
    fun unrelatedUserCannotProbeUnreadableOrderThroughRead() {
        val unreadableOrder = order(status = "UNKNOWN_STATUS")
        every { orderRepository.findById("order-1") } returns Optional.of(unreadableOrder)
        every {
            policy.requireConversationParticipant(unreadableOrder, "other-user")
        } throws OrderContractException.serviceAccessDenied()

        val error = assertThrows<OrderContractException> {
            service.requireReadAccess(conversation(), "other-user")
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals(OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED, error.errorCode)
        verify(exactly = 1) {
            policy.requireConversationParticipant(unreadableOrder, "other-user")
        }
    }

    @Test
    fun unrelatedUserCannotProbeInvalidOrderThroughSend() {
        val legacyOrder = order(paymentFlow = "LEGACY_MEDICAL")
        every { orderRepository.findByIdForUpdate("order-1") } returns legacyOrder
        every {
            policy.requireConversationParticipant(legacyOrder, "other-user")
        } throws OrderContractException.serviceAccessDenied()

        val error = assertThrows<OrderContractException> {
            service.requireSendAccess(conversation(), "other-user")
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals(OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED, error.errorCode)
        verify(exactly = 1) {
            policy.requireConversationParticipant(legacyOrder, "other-user")
        }
    }

    @Test
    fun `send authorization locks the order and only service active permits sending`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns order()
        service.requireSendAccess(conversation(), "user-1")

        every { orderRepository.findByIdForUpdate("order-1") } returns
            order(status = OrderStatusEnum.REFUND_REVIEW.value)
        val error = assertThrows<OrderContractException> {
            service.requireSendAccess(conversation(), "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_READ_ONLY, error.errorCode)
        verify(exactly = 0) { orderRepository.findById("order-1") }
    }

    @Test
    fun `completed service conversation remains readable but rejects new messages`() {
        val completed = order(status = OrderStatusEnum.COMPLETED.value)
        every { orderRepository.findById("order-1") } returns Optional.of(completed)
        every { orderRepository.findByIdForUpdate("order-1") } returns completed

        service.requireReadAccess(conversation(), "user-1")
        val error = assertThrows<OrderContractException> {
            service.requireSendAccess(conversation(), "user-1")
        }

        assertEquals(OrderContractErrorCode.ORDER_SERVICE_READ_ONLY, error.errorCode)
    }

    @Test
    fun `order lock makes a creation flush failure propagate without a second lookup`() {
        val failure = org.springframework.dao.DataIntegrityViolationException("constraint")
        every { orderRepository.findByIdForUpdate("order-1") } returns order()
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        } returns null
        every { conversationRepository.saveAndFlush(any()) } throws failure

        val thrown = assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            service.getOrCreate("order-1", "user-1")
        }

        assertSame(failure, thrown)
        verify(exactly = 1) {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                "order-1"
            )
        }
    }

    @Test
    fun `same participants can create distinct service conversations for two active orders`() {
        val saved = mutableListOf<DmConversationEntity>()
        every { orderRepository.findByIdForUpdate(any()) } answers {
            order(id = firstArg())
        }
        every {
            conversationRepository.findByConversationTypeAndOrderId(
                DmConversationEntity.ORDER_SERVICE,
                any()
            )
        } returns null
        every { conversationRepository.saveAndFlush(any()) } answers {
            firstArg<DmConversationEntity>().also(saved::add)
        }

        service.getOrCreate("order-1", "user-1")
        service.getOrCreate("order-2", "user-1")

        assertEquals(setOf("order-1", "order-2"), saved.mapNotNull { it.orderId }.toSet())
        assertTrue(saved.all { setOf(it.userAId, it.userBId) == setOf("user-1", "consultant-1") })
    }

    private fun order(
        id: String = "order-1",
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value,
        paymentFlow: String = "TRAVEL_GROUND_SERVICE_ONLY",
        activatedAt: LocalDateTime? = LocalDateTime.of(2026, 8, 22, 9, 0),
        consultantId: String = "consultant-1"
    ) = OrderEntity(
        id = id,
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        status = status,
        paymentFlow = paymentFlow,
        consultantId = consultantId,
        serviceActivatedAt = activatedAt
    )

    private fun conversation() = DmConversationEntity(
        id = "conversation-1",
        conversationType = DmConversationEntity.ORDER_SERVICE,
        orderId = "order-1",
        userAId = "consultant-1",
        userBId = "user-1"
    )
}
