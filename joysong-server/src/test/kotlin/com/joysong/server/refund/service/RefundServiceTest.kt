package com.joysong.server.refund.service

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RefundServiceTest {
    private val refundRepository = mockk<RefundRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val orderStatusLogService = mockk<OrderStatusLogService>()
    private val couponService = mockk<CouponService>()
    private val service = RefundService(
        refundRepository,
        orderRepository,
        orderStatusLogService,
        couponService
    )

    @Test
    fun `adminUpdateStatus rejects unsupported target state before loading data`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.adminUpdateStatus("refund-1", "CANCELLED", "admin-1")
        }
        verify(exactly = 0) { refundRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `adminUpdateStatus rejects repeated decision`() {
        every { refundRepository.findByIdForUpdate("refund-1") } returns refund(status = "APPROVED")
        assertThrows(IllegalArgumentException::class.java) {
            service.adminUpdateStatus("refund-1", "APPROVED", "admin-1")
        }
    }

    @Test
    fun `adminUpdateStatus requires a rejection reason`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.adminUpdateStatus("refund-1", "REJECTED", "admin-1", "  ")
        }
        verify(exactly = 0) { refundRepository.findByIdForUpdate(any()) }
    }

    private fun refund(status: String) = RefundEntity(
        id = "refund-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("100.00"),
        reason = "测试退款",
        status = status
    )
}
