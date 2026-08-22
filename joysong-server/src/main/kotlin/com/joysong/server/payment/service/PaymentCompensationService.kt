package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentCompensationStatus
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.ProviderRefundRequest
import org.springframework.stereotype.Service

/** Executes the manually reviewed original-channel refund for a provider-confirmed anomalous charge. */
@Service
class PaymentCompensationService(
    private val persistenceService: PaymentCompensationPersistenceService,
    private val paymentGatewayRegistry: PaymentGatewayRegistry
) {
    fun retryCompensationRefund(caseId: String, adminId: String) = run {
        val compensation = persistenceService.prepareRefund(caseId, adminId)
        if (compensation.status == PaymentCompensationStatus.SUCCEEDED.name) return@run compensation
        val gateway = paymentGatewayRegistry.require(PaymentProvider.parse(compensation.provider))
        try {
            val result = if (!compensation.providerRefundId.isNullOrBlank()) {
                gateway.queryRefund(compensation.providerRefundId)
            } else {
                gateway.refund(
                    ProviderRefundRequest(
                        refundItemId = compensation.id,
                        providerPaymentId = compensation.providerPaymentId,
                        amountMinor = compensation.amountMinor,
                        currency = compensation.currency,
                        idempotencyKey = compensation.idempotencyKey
                    )
                )
            }
            persistenceService.applyProviderResult(compensation.id, result)
        } catch (error: PaymentProviderException) {
            persistenceService.markProviderError(
                compensation.id,
                if (error.outcomeUnknown || error.retryable) {
                    PaymentCompensationStatus.PROCESSING
                } else {
                    PaymentCompensationStatus.FAILED
                },
                error.errorCode,
                error.message
            )
            throw error
        } catch (error: Exception) {
            persistenceService.markProviderError(
                compensation.id,
                PaymentCompensationStatus.PROCESSING,
                "PAYMENT_COMPENSATION_REQUEST_UNCERTAIN",
                error.message
            )
            throw error
        }
    }

    fun adminListAll() = persistenceService.listAll()
}
