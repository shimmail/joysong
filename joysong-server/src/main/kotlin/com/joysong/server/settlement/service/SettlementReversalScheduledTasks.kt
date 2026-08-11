package com.joysong.server.settlement.service

import com.joysong.server.refund.repository.RefundRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Retries the idempotent ledger side effect for already-finalized refunds. */
@Component
class SettlementReversalScheduledTasks(
    private val refundRepository: RefundRepository,
    private val reversalService: SettlementReversalService
) {
    @Scheduled(fixedDelay = 300_000)
    fun retryFinalizedRefundReversals() {
        refundRepository.findTop50ByStatusOrderByUpdatedAtAsc(COMPLETED_REFUND_STATUS).forEach { refund ->
            try {
                reversalService.reverseCompletedRefund(refund.id)
            } catch (error: Exception) {
                log.error("重试退款账本冲正失败[refundId={}]: {}", refund.id, error.message, error)
            }
        }
    }

    private companion object {
        const val COMPLETED_REFUND_STATUS = "APPROVED"
        val log = LoggerFactory.getLogger(SettlementReversalScheduledTasks::class.java)
    }
}
