package com.joysong.server.admin.controller

import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.service.RefundService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class AdminRefundControllerTest {

    @Test
    fun `retry endpoint forwards the authenticated admin to failed item retry`() {
        val refundService = mockk<RefundService>()
        val authentication = mockk<Authentication>()
        val refund = RefundEntity(
            id = "refund-1",
            orderId = "order-1",
            userId = "user-1",
            amount = BigDecimal("400.00"),
            reason = "retry",
            originalStatus = "SERVICE_ACTIVE",
            status = "PROCESSING"
        )
        every { authentication.principal } returns "admin-1"
        every { refundService.retryFailedProcessingRefund("refund-1", "admin-1") } returns refund

        val response = AdminRefundController(refundService).retryRefund("refund-1", authentication)

        assertEquals(200, response.code)
        assertEquals(refund, response.data)
        verify(exactly = 1) { refundService.retryFailedProcessingRefund("refund-1", "admin-1") }
    }
}
