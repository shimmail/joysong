package com.joysong.server.payment.dto

import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentNextAction
import com.joysong.server.payment.service.PaymentSessionResult
import java.time.LocalDateTime

data class PaymentNextActionResponse(
    val type: String,
    val clientSecret: String? = null,
    val url: String? = null,
    val params: Map<String, String>? = null,
    val orderString: String? = null
) {
    companion object {
        fun from(action: PaymentNextAction): PaymentNextActionResponse = when (action) {
            is PaymentNextAction.StripeClientSecret -> PaymentNextActionResponse(
                type = action.type,
                clientSecret = action.clientSecret
            )
            is PaymentNextAction.Redirect -> PaymentNextActionResponse(
                type = action.type,
                url = action.url
            )
            is PaymentNextAction.WeChatSdkParams -> PaymentNextActionResponse(
                type = action.type,
                params = action.params
            )
            is PaymentNextAction.AlipayOrderString -> PaymentNextActionResponse(
                type = action.type,
                orderString = action.orderString
            )
        }
    }
}

data class PaymentAttemptResponse(
    val id: String,
    val orderId: String,
    val paymentType: String,
    val provider: String,
    val paymentMethod: String?,
    val currency: String,
    val amountMinor: Long?,
    val status: String,
    val providerPaymentId: String?,
    val failureCode: String?,
    val failureMessage: String?,
    val nextAction: PaymentNextActionResponse?,
    val expiresAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?
) {
    companion object {
        fun from(session: PaymentSessionResult): PaymentAttemptResponse =
            from(session.payment, session.nextAction)

        fun from(payment: PaymentEntity, nextAction: PaymentNextAction? = null) = PaymentAttemptResponse(
            id = payment.id,
            orderId = payment.orderId,
            paymentType = payment.paymentType,
            provider = payment.provider,
            paymentMethod = payment.paymentMethod,
            currency = payment.currency,
            amountMinor = payment.amountMinor,
            status = payment.status,
            providerPaymentId = payment.providerPaymentId,
            failureCode = payment.failureCode,
            failureMessage = payment.failureMessage,
            nextAction = nextAction?.let(PaymentNextActionResponse::from),
            expiresAt = payment.expiresAt,
            createdAt = payment.createdAt,
            updatedAt = payment.updatedAt
        )
    }
}
