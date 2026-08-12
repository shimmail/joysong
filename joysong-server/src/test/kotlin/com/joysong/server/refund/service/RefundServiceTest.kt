package com.joysong.server.refund.service

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.service.SettlementReversalService
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

    @Test
    fun `retry invokes reversal only after a completed provider refund is finalized`() {
        val execution = mockk<RefundExecutionService>()
        val workflow = mockk<RefundWorkflowPersistenceService>()
        val reversal = mockk<SettlementReversalService>()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        val finalized = FinalizedRefund(processing.copy(status = RefundWorkflowPersistenceService.APPROVED), order())
        val completed = RefundExecutionOutcome(refundedAmountMinor = 100, completed = true)
        val service = RefundService(
            refundRepository,
            orderRepository,
            orderStatusLogService,
            couponService,
            execution,
            workflow,
            reversal
        )
        every { refundRepository.findById("refund-1") } returns java.util.Optional.of(processing)
        every { execution.execute(processing) } returns completed
        every { workflow.finalizeSuccess("refund-1", completed, "SYSTEM", "SYSTEM", any()) } returns finalized
        every { reversal.reverseCompletedRefund("refund-1") } returns Unit

        service.retryProcessingRefund("refund-1")

        verify { workflow.finalizeSuccess("refund-1", completed, "SYSTEM", "SYSTEM", any()) }
        verify { reversal.reverseCompletedRefund("refund-1") }
    }

    @Test
    fun `retry does not invoke reversal while provider refund is incomplete`() {
        val execution = mockk<RefundExecutionService>()
        val reversal = mockk<SettlementReversalService>()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        val service = RefundService(
            refundRepository,
            orderRepository,
            orderStatusLogService,
            couponService,
            execution,
            RefundWorkflowPersistenceService(refundRepository, orderRepository, orderStatusLogService),
            reversal
        )
        every { refundRepository.findById("refund-1") } returns java.util.Optional.of(processing)
        every { execution.execute(processing) } returns RefundExecutionOutcome(refundedAmountMinor = 0, completed = false)
        every { refundRepository.findById("refund-1") } returns java.util.Optional.of(processing)

        service.retryProcessingRefund("refund-1")

        verify(exactly = 0) { reversal.reverseCompletedRefund(any()) }
    }

    private fun refund(status: String) = RefundEntity(
        id = "refund-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("100.00"),
        reason = "测试退款",
        status = status
    )

    private fun order() = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("100.00"),
        status = "REFUNDED"
    )
}
