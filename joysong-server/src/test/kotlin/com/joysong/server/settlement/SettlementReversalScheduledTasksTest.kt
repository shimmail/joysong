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
        val first = RefundEntity(id = "refund-1", orderId = "order-1", userId = "user-1", amount = BigDecimal.ONE, reason = "test", status = "APPROVED")
        val second = RefundEntity(id = "refund-2", orderId = "order-2", userId = "user-2", amount = BigDecimal.ONE, reason = "test", status = "APPROVED")
        every { repository.findTop50ByStatusOrderByUpdatedAtAsc("APPROVED") } returns listOf(first, second)
        every { service.reverseCompletedRefund("refund-1") } throws IllegalStateException("ledger unavailable")
        every { service.reverseCompletedRefund("refund-2") } returns Unit

        SettlementReversalScheduledTasks(repository, service).retryFinalizedRefundReversals()

        verify(exactly = 1) { service.reverseCompletedRefund("refund-1") }
        verify(exactly = 1) { service.reverseCompletedRefund("refund-2") }
    }
}
