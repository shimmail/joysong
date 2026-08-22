package com.joysong.server.payment.provider

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

/** Development-only adapter for exercising the real local payment and refund state machines. */
@Component
@Profile("dev & !prod")
@ConditionalOnProperty(
    prefix = "payment.alipay-plus",
    name = ["simulated-enabled"],
    havingValue = "true"
)
class SimulatedAlipayPlusPaymentGateway(
    private val clock: Clock = Clock.systemDefaultZone()
) : PaymentGateway {
    override val provider: PaymentProvider = PaymentProvider.ALIPAY_PLUS

    override fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult =
        ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "simulated-alipay-plus-payment-${request.paymentId}",
            providerTransactionId = "simulated-alipay-plus-transaction-${request.paymentId}",
            amountMinor = request.amountMinor,
            currency = request.currency,
            paidAt = LocalDateTime.now(clock)
        )

    override fun recoverPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult =
        createPayment(request)

    override fun refund(request: ProviderRefundRequest): ProviderRefundResult {
        if (!request.providerPaymentId.startsWith("simulated-alipay-plus-payment-")) {
            throw PaymentProviderException(
                errorCode = "SIMULATED_PAYMENT_ID_REQUIRED",
                retryable = false,
                outcomeUnknown = false
            )
        }
        return ProviderRefundResult(
            status = PaymentStatus.SUCCEEDED,
            providerRefundId = "simulated-alipay-plus-refund-${request.refundItemId}"
        )
    }
}
