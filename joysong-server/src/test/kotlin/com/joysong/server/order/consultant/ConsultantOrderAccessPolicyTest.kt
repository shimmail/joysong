package com.joysong.server.order.consultant

import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderContractErrorCode
import com.joysong.server.order.service.OrderContractException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.time.LocalDateTime

class ConsultantOrderAccessPolicyTest {
    private val identities = mockk<IdentityAuthorizationService>()
    private val policy = ConsultantOrderAccessPolicy(identities)

    @Test
    fun revokedConsultantGetsStableForbiddenError() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns false

        val error = assertThrows<OrderContractException> {
            policy.requireActiveConsultant("consultant-1")
        }

        assertEquals(HttpStatus.FORBIDDEN, error.status)
        assertEquals(OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED, error.errorCode)
    }

    @Test
    fun workbenchOrderRevalidatesActiveConsultantRole() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns false

        val error = assertThrows<OrderContractException> {
            policy.requireWorkbenchOrder(order(), "consultant-1")
        }

        assertEquals(HttpStatus.FORBIDDEN, error.status)
        assertEquals(OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED, error.errorCode)
        verify(exactly = 1) { identities.hasActiveRole("consultant-1", "CONSULTANT") }
    }

    @Test
    fun consumerParticipantDoesNotRequireConsultantRole() {
        policy.requireConversationParticipant(order(userId = "user-1"), "user-1")
        verify(exactly = 0) { identities.hasActiveRole(any(), any()) }
    }

    @Test
    fun consultantParticipantIsRevalidatedOnEveryConversationAccess() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns true
        policy.requireConversationParticipant(order(consultantId = "consultant-1"), "consultant-1")
        verify(exactly = 1) { identities.hasActiveRole("consultant-1", "CONSULTANT") }
    }

    @Test
    fun overlappingConsumerAndConsultantStillRequiresActiveConsultantRole() {
        every { identities.hasActiveRole("shared-1", "CONSULTANT") } returns false

        val error = assertThrows<OrderContractException> {
            policy.requireConversationParticipant(
                order(userId = "shared-1", consultantId = "shared-1"),
                "shared-1"
            )
        }

        assertEquals(HttpStatus.FORBIDDEN, error.status)
        assertEquals(OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED, error.errorCode)
        verify(exactly = 1) { identities.hasActiveRole("shared-1", "CONSULTANT") }
    }

    @Test
    fun selfAssignedOrderIsNotEligibleForTheConsultantWorkbench() {
        every { identities.hasActiveRole("shared-1", "CONSULTANT") } returns true

        val error = assertThrows<OrderContractException> {
            policy.requireWorkbenchOrder(
                order(userId = "shared-1", consultantId = "shared-1"),
                "shared-1"
            )
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals(OrderContractErrorCode.CONSULTANT_ORDER_NOT_FOUND, error.errorCode)
    }

    private fun order(
        userId: String = "user-1",
        consultantId: String = "consultant-1",
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value
    ) = OrderEntity(
        id = "order-1",
        userId = userId,
        consultantId = consultantId,
        projectName = "项目",
        price = BigDecimal.ZERO,
        status = status,
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        serviceActivatedAt = LocalDateTime.of(2026, 8, 29, 10, 0)
    )
}
