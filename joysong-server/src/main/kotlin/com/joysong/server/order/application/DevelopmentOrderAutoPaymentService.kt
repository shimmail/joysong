package com.joysong.server.order.application

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.service.PaymentService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

data class DevelopmentOrderAutoPaymentResult(
    val successful: Boolean,
    val payment: PaymentEntity? = null,
    val failureCode: String? = null,
    val outcomeUnknown: Boolean = false
)

/** Development-only adapter that delegates all money movement to the real payment orchestration. */
@Service
@Profile("dev & !prod")
@ConditionalOnProperty(
    prefix = "payment",
    name = ["alipay-plus.simulated-enabled", "development.order-auto-pay-enabled"],
    havingValue = "true",
    matchIfMissing = false
)
class DevelopmentOrderAutoPaymentService(
    private val paymentService: PaymentService
) {
    fun attempt(orderId: String, userId: String): DevelopmentOrderAutoPaymentResult = try {
        val payment = paymentService.createPaymentSession(
            orderId = orderId,
            userId = userId,
            paymentType = PaymentType.TRAVEL_GROUND_SERVICE_FEE,
            provider = PaymentProvider.ALIPAY_PLUS,
            paymentMethod = "ALIPAY_PLUS_CASHIER",
            idempotencyKey = "dev-order-autopay-$orderId"
        ).payment
        if (payment.status in PaymentStatus.successfulDatabaseValues) {
            DevelopmentOrderAutoPaymentResult(successful = true, payment = payment)
        } else {
            log.warn(
                "Development order auto-payment did not succeed: orderId={}, paymentStatus={}",
                orderId,
                payment.status
            )
            DevelopmentOrderAutoPaymentResult(
                successful = false,
                payment = payment,
                failureCode = payment.status,
                outcomeUnknown = payment.status == PaymentStatus.PROCESSING.name
            )
        }
    } catch (error: PaymentProviderException) {
        log.warn(
            "Development order auto-payment provider failure: orderId={}, errorCode={}, outcomeUnknown={}",
            orderId,
            error.errorCode,
            error.outcomeUnknown
        )
        DevelopmentOrderAutoPaymentResult(
            successful = false,
            failureCode = error.errorCode,
            outcomeUnknown = error.outcomeUnknown
        )
    } catch (error: Exception) {
        log.warn(
            "Development order auto-payment failed: orderId={}, errorType={}",
            orderId,
            error.javaClass.simpleName
        )
        DevelopmentOrderAutoPaymentResult(
            successful = false,
            failureCode = "DEVELOPMENT_AUTO_PAYMENT_FAILED",
            outcomeUnknown = true
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(DevelopmentOrderAutoPaymentService::class.java)
    }
}
