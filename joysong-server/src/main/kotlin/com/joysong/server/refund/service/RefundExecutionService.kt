package com.joysong.server.refund.service

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.ProviderRefundRequest
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundItemRepository
import org.springframework.stereotype.Service

data class RefundExecutionOutcome(
    val refundedAmountMinor: Long,
    val completed: Boolean
)

/** Provider calls are outside transactions; each local update is a short transaction. */
@Service
class RefundExecutionService(
    private val paymentRepository: PaymentRepository,
    private val refundItemRepository: RefundItemRepository,
    private val paymentGatewayRegistry: PaymentGatewayRegistry,
    private val persistenceService: RefundItemPersistenceService
) {
    fun retryFailed(refund: RefundEntity): RefundExecutionOutcome {
        val retryItemIds = persistenceService.requeueFailedItems(refund.id).mapTo(mutableSetOf()) { it.id }
        return execute(refund, retryItemIds)
    }

    fun execute(refund: RefundEntity): RefundExecutionOutcome = execute(refund, null)

    private fun execute(
        refund: RefundEntity,
        retryItemIds: Set<String>?
    ): RefundExecutionOutcome {
        val target = requireNotNull(refund.requestedAmountMinor) { "REFUND_AMOUNT_SNAPSHOT_MISSING" }
        val items = persistenceService.prepareItems(refund)

        for (item in items.filter {
            (retryItemIds == null || it.id in retryItemIds) &&
                (it.status == PaymentStatus.CREATED.name || it.status == PaymentStatus.PROCESSING.name)
        }) {
            val payment = paymentRepository.findById(item.paymentId)
                .orElseThrow { IllegalArgumentException("PAYMENT_NOT_FOUND") }
            val provider = PaymentProvider.parse(payment.provider)
            try {
                val gateway = paymentGatewayRegistry.require(provider)
                val result = if (!item.providerRefundId.isNullOrBlank()) {
                    gateway.queryRefund(item.providerRefundId)
                } else {
                    val providerPaymentId = payment.providerPaymentId
                        ?: throw IllegalStateException("PROVIDER_PAYMENT_ID_MISSING")
                    gateway.refund(
                        ProviderRefundRequest(
                            refundItemId = item.id,
                            providerPaymentId = providerPaymentId,
                            amountMinor = item.amountMinor,
                            currency = item.currency,
                            idempotencyKey = "refund-${refund.id}-${payment.id}"
                        )
                    )
                }
                persistenceService.applyProviderResult(item.id, result)
            } catch (error: PaymentProviderException) {
                persistenceService.markProviderError(
                    item.id,
                    if (error.outcomeUnknown || error.retryable) PaymentStatus.PROCESSING else PaymentStatus.FAILED,
                    error.errorCode,
                    error.message
                )
            } catch (error: Exception) {
                persistenceService.markProviderError(
                    item.id,
                    PaymentStatus.PROCESSING,
                    "REFUND_REQUEST_UNCERTAIN",
                    error.message
                )
            }
        }
        return persistenceService.summarize(refund.id, target)
    }
}
