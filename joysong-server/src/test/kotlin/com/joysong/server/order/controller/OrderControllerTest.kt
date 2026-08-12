package com.joysong.server.order.controller

import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.service.PaymentService
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.service.RefundService
import com.joysong.server.review.service.ReviewService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.wallet.dto.ConsumerSettlementDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class OrderControllerTest {
    @Test
    fun `another consumer gets not found before settlement lookup`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { authentication.principal } returns "other-user"
        every { orders.getOrderById("order-1", "other-user") } returns null
        val controller = controller(orders, settlements)

        val response = controller.getSettlement("order-1", authentication)

        assertEquals(404, response.code)
        verify(exactly = 0) { settlements.findByOrderId(any()) }
    }

    @Test
    fun `owned order without settlement has distinct not generated response`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns OrderEntity(
            id = "order-1", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "COMPLETED"
        )
        every { settlements.findByOrderId("order-1") } returns null
        val controller = controller(orders, settlements)

        val response = controller.getSettlement("order-1", authentication)

        assertEquals(409, response.code)
        assertEquals("SETTLEMENT_NOT_GENERATED", response.message)
    }

    @Test
    fun `owned settlement reports gross successful payments separately from net after refunds`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val payments = mockk<PaymentRepository>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns OrderEntity(
            id = "order-1", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "COMPLETED"
        )
        every { settlements.findByOrderId("order-1") } returns SettlementEntity(
            id = 5, orderId = "order-1", currency = "USD", totalAmountMinor = 800, totalAmount = BigDecimal("8.00")
        )
        every { payments.sumSucceededAmountMinor("order-1") } returns 1000
        val controller = controller(orders, settlements, payments)

        val response = controller.getSettlement("order-1", authentication)
        val data = response.data as ConsumerSettlementDto

        assertEquals(1000, data.grossTotalPaid.minor)
        assertEquals(800, data.netSettled.minor)
        assertEquals("USD", data.grossTotalPaid.currency)
    }

    private fun controller(
        orders: OrderService,
        settlements: SettlementRepository,
        payments: PaymentRepository = mockk()
    ) = OrderController(
        orders, mockk<PaymentService>(), mockk<RefundService>(), mockk<ReviewService>(), mockk<OrderStatusLogService>(), settlements, payments
    )
}
