package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentType
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** Business guardrails that apply only to the isolated demo/UAT profile. */
@Component
class DemoPaymentPolicy(
    environment: Environment,
    @Value("\${payment.alipay-plus.simulated-enabled:false}") simulatedAlipayPlusEnabled: Boolean,
) {
    private val demoProfile = environment.activeProfiles.any { it.equals("demo", ignoreCase = true) }
    private val simulatedUat = demoProfile && simulatedAlipayPlusEnabled

    fun requirePaymentTypeAllowed(paymentType: PaymentType) {
        if (simulatedUat) {
            require(paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE) {
                "UAT_SIMULATED_PAYMENT_TYPE_NOT_SUPPORTED"
            }
        }
    }

    fun requiresManualRefundReview(): Boolean = demoProfile
}
