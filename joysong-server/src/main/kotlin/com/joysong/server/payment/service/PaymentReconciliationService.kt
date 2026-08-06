package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.repository.PaymentEventRepository
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.refund.service.RefundService
import com.joysong.server.refund.service.RefundWorkflowPersistenceService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
@ConditionalOnProperty(
    prefix = "payment.reconciliation",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class PaymentReconciliationService(
    private val paymentRepository: PaymentRepository,
    private val paymentEventRepository: PaymentEventRepository,
    private val refundRepository: RefundRepository,
    private val paymentService: PaymentService,
    private val paymentWebhookService: PaymentWebhookService,
    private val refundService: RefundService,
    @Value("\${payment.reconciliation.stale-after-seconds:120}")
    private val staleAfterSeconds: Long
) {
    companion object {
        private val log = LoggerFactory.getLogger(PaymentReconciliationService::class.java)
    }

    @Scheduled(fixedDelayString = "\${payment.reconciliation.fixed-delay-ms:60000}")
    fun reconcile() {
        reconcilePayments()
        retryEvents()
        reconcileRefunds()
    }

    private fun reconcilePayments() {
        val staleBefore = LocalDateTime.now().minusSeconds(staleAfterSeconds.coerceAtLeast(30))
        paymentRepository.findTop100ByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            listOf(
                PaymentStatus.CREATED.name,
                PaymentStatus.REQUIRES_ACTION.name,
                PaymentStatus.PROCESSING.name
            ),
            staleBefore
        ).forEach { payment ->
            runCatching { paymentService.reconcilePayment(payment.id) }
                .onFailure { log.warn("支付补偿失败 paymentId={}: {}", payment.id, it.message) }
        }
    }

    private fun retryEvents() {
        paymentEventRepository.findTop50ByProcessingStatusAndRetryCountLessThanOrderByReceivedAtAsc(
            "FAILED",
            10
        ).forEach { event ->
            runCatching { paymentWebhookService.retryFailedEvent(event.id) }
                .onFailure { log.warn("支付事件补偿失败 eventId={}: {}", event.id, it.message) }
        }
    }

    private fun reconcileRefunds() {
        refundRepository.findTop50ByStatusOrderByUpdatedAtAsc(
            RefundWorkflowPersistenceService.PROCESSING
        ).forEach { refund ->
            runCatching { refundService.retryProcessingRefund(refund.id) }
                .onFailure { log.warn("退款补偿失败 refundId={}: {}", refund.id, it.message) }
        }
    }
}
