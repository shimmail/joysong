package com.joysong.server.payment.service

import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

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

    @Test
    fun `travel service order cannot query a medical payment stage`() {
        val payments = mockk<PaymentRepository>()
        val orders = mockk<OrderRepository>()
        every { orders.findById("order-1") } returns Optional.of(
            OrderEntity(
                id = "order-1",
                userId = "user-1",
                projectName = "项目",
                price = BigDecimal("400.00"),
                paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
                status = "SERVICE_ACTIVE"
            )
        )
        val service = PaymentService(payments, orders, mockk())

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.getLatestPayment("order-1", "user-1", PaymentType.BALANCE)
        }

        assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", error.message)
        verify(exactly = 0) {
            payments.findFirstByOrderIdAndPaymentTypeOrderByCreatedAtDesc(any(), any())
        }
    }
}
