package com.joysong.server.payment.service

import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PaymentServiceSafetyTest {

    private fun disabledService() = PaymentService(
        paymentRepository = mockk<PaymentRepository>(),
        orderRepository = mockk<OrderRepository>(),
        orderStatusLogService = mockk<OrderStatusLogService>()
    )

    @Test
    fun `consultation payment cannot simulate success when provider is disabled`() {
        val error = assertThrows(IllegalStateException::class.java) {
            disabledService().payConsultationFee("order-1", "user-1")
        }

        assertEquals("LEGACY_PAYMENT_ENDPOINT_REMOVED", error.message)
    }

    @Test
    fun `balance payment cannot simulate success when provider is disabled`() {
        val error = assertThrows(IllegalStateException::class.java) {
            disabledService().payBalance("order-1", "user-1")
        }

        assertEquals("LEGACY_PAYMENT_ENDPOINT_REMOVED", error.message)
    }
}
