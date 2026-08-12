package com.joysong.server.settlement.service

import com.joysong.server.refund.repository.RefundRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.data.domain.PageRequest

/** Retries the idempotent ledger side effect for already-finalized refunds. */
@Component
class SettlementReversalScheduledTasks(
    private val refundRepository: RefundRepository,
    private val reversalService: SettlementReversalService
) {
    @Scheduled(fixedDelay = 300_000)
    fun retryFinalizedRefundReversals() {
        var afterId = ""
        do {
            val batch = refundRepository.findPendingRevenueReversalsAfter(
                COMPLETED_REFUND_STATUS,
                PENDING_REVERSAL_STATUS,
                afterId,
                PageRequest.of(0, BATCH_SIZE)
            )
            batch.forEach { refund ->
                try {
                    reversalService.reverseCompletedRefund(refund.id)
                } catch (error: Exception) {
                    log.error("重试退款账本冲正失败[refundId={}]: {}", refund.id, error.message, error)
                }
            }
            afterId = batch.lastOrNull()?.id ?: afterId
        } while (batch.size == BATCH_SIZE)
    }

    private companion object {
        const val COMPLETED_REFUND_STATUS = "APPROVED"
        const val PENDING_REVERSAL_STATUS = "PENDING"
        const val BATCH_SIZE = 50
        val log = LoggerFactory.getLogger(SettlementReversalScheduledTasks::class.java)
    }
}
