package com.joysong.server.payment.provider

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import org.springframework.stereotype.Component
import java.time.LocalDateTime

data class ProviderCreatePaymentRequest(
    val paymentId: String,
    val orderId: String,
    val amountMinor: Long,
    val currency: String,
    val paymentMethod: String,
    /** Stable across retries even if the first provider response is lost. */
    val idempotencyKey: String
)

data class ProviderConfirmPaymentRequest(
    val paymentId: String,
    val providerPaymentId: String,
    val idempotencyKey: String
)

sealed interface PaymentNextAction {
    val type: String

    data class Redirect(val url: String) : PaymentNextAction {
        override val type: String = "REDIRECT"
    }
}

data class ProviderPaymentResult(
    val status: PaymentStatus,
    val providerPaymentId: String,
    val providerTransactionId: String? = null,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val nextAction: PaymentNextAction? = null,
    val expiresAt: LocalDateTime? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null
)

data class ProviderRefundRequest(
    val refundItemId: String,
    val providerPaymentId: String,
    val amountMinor: Long,
    val currency: String,
    val idempotencyKey: String
)

data class ProviderRefundResult(
    val status: PaymentStatus,
    val providerRefundId: String,
    val failureCode: String? = null,
    val failureMessage: String? = null
)

class PaymentProviderException(
    val errorCode: String,
    val retryable: Boolean,
    /** True when the request may have reached the provider. */
    val outcomeUnknown: Boolean,
    message: String = errorCode,
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface PaymentGateway {
    val provider: PaymentProvider
    fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult

    fun queryPayment(providerPaymentId: String): ProviderPaymentResult {
        throw PaymentProviderException("PAYMENT_QUERY_UNAVAILABLE", retryable = false, outcomeUnknown = false)
    }

    fun confirmPayment(request: ProviderConfirmPaymentRequest): ProviderPaymentResult {
        throw PaymentProviderException("PAYMENT_CONFIRM_UNAVAILABLE", retryable = false, outcomeUnknown = false)
    }

    fun refund(request: ProviderRefundRequest): ProviderRefundResult {
        throw PaymentProviderException("REFUND_PROVIDER_UNAVAILABLE", retryable = false, outcomeUnknown = false)
    }

    fun queryRefund(providerRefundId: String): ProviderRefundResult {
        throw PaymentProviderException("REFUND_QUERY_UNAVAILABLE", retryable = false, outcomeUnknown = false)
    }

    fun verifyWebhook(payload: String, headers: Map<String, String>): VerifiedProviderEvent {
        throw PaymentProviderException("PAYMENT_WEBHOOK_UNAVAILABLE", retryable = false, outcomeUnknown = false)
    }
}

data class VerifiedProviderEvent(
    val providerEventId: String,
    val eventType: String,
    val providerPaymentId: String,
    val providerTransactionId: String? = null,
    val paymentStatus: PaymentStatus,
    val amountMinor: Long? = null,
    val currency: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    /** Local ID copied into provider metadata; used if a webhook races the create response. */
    val localPaymentId: String? = null
)

@Component
class PaymentGatewayRegistry(gateways: List<PaymentGateway>) {
    private val byProvider = gateways.associateBy { it.provider }

    fun require(provider: PaymentProvider): PaymentGateway =
        byProvider[provider] ?: throw PaymentProviderException(
            "PAYMENT_PROVIDER_UNAVAILABLE",
            retryable = false,
            outcomeUnknown = false
        )
}
