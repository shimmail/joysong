package com.joysong.server.settlement

import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.service.SettlementReversalScheduledTasks
import com.joysong.server.settlement.service.SettlementReversalService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SettlementReversalScheduledTasksTest {
    @Test
    fun `finalized refunds are retried independently`() {
        val repository = mockk<RefundRepository>()
        val service = mockk<SettlementReversalService>()
        val first = refund("refund-1")
        val second = refund("refund-2")
        every { repository.findPendingRevenueReversalsAfter("APPROVED", "PENDING", "", any()) } returns listOf(first, second)
        every { service.reverseCompletedRefund("refund-1") } throws IllegalStateException("ledger unavailable")
        every { service.reverseCompletedRefund("refund-2") } returns Unit

        SettlementReversalScheduledTasks(repository, service).retryFinalizedRefundReversals()

        verify(exactly = 1) { service.reverseCompletedRefund("refund-1") }
        verify(exactly = 1) { service.reverseCompletedRefund("refund-2") }
    }

    @Test
    fun `cursor pagination reaches pending reversals beyond the oldest fifty`() {
        val repository = mockk<RefundRepository>()
        val service = mockk<SettlementReversalService>()
        val firstPage = (1..50).map { refund("refund-${it.toString().padStart(3, '0')}") }
        val secondPage = listOf(refund("refund-051"))
        every { repository.findPendingRevenueReversalsAfter("APPROVED", "PENDING", "", any()) } returns firstPage
        every { repository.findPendingRevenueReversalsAfter("APPROVED", "PENDING", "refund-050", any()) } returns secondPage
        every { service.reverseCompletedRefund(any()) } returns Unit

        SettlementReversalScheduledTasks(repository, service).retryFinalizedRefundReversals()

        verify(exactly = 1) { service.reverseCompletedRefund("refund-051") }
        verify(exactly = 51) { service.reverseCompletedRefund(any()) }
    }

    private fun refund(id: String) = RefundEntity(
        id = id, orderId = "order-$id", userId = "user-$id", amount = BigDecimal.ONE,
        reason = "test", status = "APPROVED"
    )
}
