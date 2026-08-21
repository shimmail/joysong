package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundItemRepository
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Blocks Stripe adapter retirement while historical provider duties remain. */
@Component
class StripeLegacyPaymentGuard(
    private val paymentRepository: PaymentRepository,
    private val refundItemRepository: RefundItemRepository,
    @Value("\${payment.stripe.legacy-enabled:false}")
    private val legacyEnabled: String
) : SmartInitializingSingleton {

    @Transactional(readOnly = true)
    override fun afterSingletonsInstantiated() {
        if (legacyEnabled.equals("true", ignoreCase = true)) return
        if (!legacyEnabled.equals("false", ignoreCase = true)) {
            throw IllegalStateException(INVALID_CONFIGURATION_CODE)
        }

        val hasLiabilities = try {
            paymentRepository.countActionableLiabilitiesByProvider(
                provider = PaymentProvider.STRIPE.name,
                inFlightStatuses = IN_FLIGHT_PAYMENT_STATUSES,
                refundableStatuses = REFUNDABLE_PAYMENT_STATUSES,
                knownStatuses = KNOWN_PAYMENT_STATUSES
            ) > 0 || refundItemRepository.countUnresolvedLiabilitiesByProvider(
                provider = PaymentProvider.STRIPE.name,
                successfulStatuses = SUCCESSFUL_REFUND_STATUSES
            ) > 0
        } catch (error: Exception) {
            throw adapterRequired(error)
        }

        if (hasLiabilities) throw adapterRequired()
    }

    private fun adapterRequired(cause: Throwable? = null) =
        IllegalStateException(ERROR_CODE, cause)

    companion object {
        const val ERROR_CODE = "STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER"
        const val INVALID_CONFIGURATION_CODE = "STRIPE_LEGACY_ENABLED_INVALID"

        private val IN_FLIGHT_PAYMENT_STATUSES = listOf(
            PaymentStatus.CREATED.name,
            PaymentStatus.REQUIRES_ACTION.name,
            PaymentStatus.PROCESSING.name
        )
        private val REFUNDABLE_PAYMENT_STATUSES = listOf(
            PaymentStatus.SUCCEEDED.name,
            "SUCCESS",
            PaymentStatus.PARTIALLY_REFUNDED.name,
            PaymentStatus.REFUNDED.name
        )
        private val KNOWN_PAYMENT_STATUSES =
            (PaymentStatus.entries.map(PaymentStatus::name) + "SUCCESS").distinct()
        private val SUCCESSFUL_REFUND_STATUSES = PaymentStatus.successfulDatabaseValues
    }
}
