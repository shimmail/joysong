package com.joysong.server.payment.provider

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

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

    data class StripeClientSecret(val clientSecret: String) : PaymentNextAction {
        override val type: String = "STRIPE_CLIENT_SECRET"
    }

    data class Redirect(val url: String) : PaymentNextAction {
        override val type: String = "REDIRECT"
    }

    data class WeChatSdkParams(val params: Map<String, String>) : PaymentNextAction {
        override val type: String = "WECHAT_SDK_PARAMS"
    }

    data class AlipayOrderString(val orderString: String) : PaymentNextAction {
        override val type: String = "ALIPAY_ORDER_STRING"
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
    val failureMessage: String? = null
)

@Component
class DemoPaymentGateway(
    @Value("\${payment.mode:disabled}") private val paymentMode: String
) : PaymentGateway {
    override val provider: PaymentProvider = PaymentProvider.DEMO

    override fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult {
        requireDemoMode("PAYMENT_PROVIDER_UNAVAILABLE")
        val transactionId = UUID.nameUUIDFromBytes(request.idempotencyKey.toByteArray())
            .toString().replace("-", "").take(24)
        return ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "demo_${request.paymentId}",
            providerTransactionId = transactionId,
            amountMinor = request.amountMinor,
            currency = request.currency
        )
    }

    override fun queryPayment(providerPaymentId: String): ProviderPaymentResult {
        requireDemoMode("PAYMENT_QUERY_UNAVAILABLE")
        return ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = providerPaymentId,
            providerTransactionId = providerPaymentId.removePrefix("demo_").replace("-", "").take(24)
        )
    }

    override fun confirmPayment(request: ProviderConfirmPaymentRequest): ProviderPaymentResult =
        queryPayment(request.providerPaymentId)

    override fun refund(request: ProviderRefundRequest): ProviderRefundResult {
        requireDemoMode("REFUND_PROVIDER_UNAVAILABLE")
        return ProviderRefundResult(
            status = PaymentStatus.SUCCEEDED,
            providerRefundId = "demo_refund_${request.refundItemId}"
        )
    }

    override fun queryRefund(providerRefundId: String): ProviderRefundResult {
        requireDemoMode("REFUND_QUERY_UNAVAILABLE")
        return ProviderRefundResult(PaymentStatus.SUCCEEDED, providerRefundId)
    }

    private fun requireDemoMode(errorCode: String) {
        if (!paymentMode.equals("demo", ignoreCase = true)) {
            throw PaymentProviderException(errorCode, retryable = false, outcomeUnknown = false)
        }
    }
}

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
