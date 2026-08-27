package com.joysong.server.payment.domain


enum class PaymentProvider {
    DEMO,
    STRIPE,
    PAYPAL,
    WECHAT_PAY,
    ALIPAY,
    ALIPAY_PLUS;

    companion object {
        fun parse(value: String): PaymentProvider =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("UNSUPPORTED_PAYMENT_PROVIDER")
    }
}
enum class PaymentType {
    CONSULTATION_FEE,
    BALANCE,
    TRAVEL_GROUND_SERVICE_FEE
}

enum class PaymentStatus {
    CREATED,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    companion object {
        /** Compatibility with records created before provider-aware payments. */
        val successfulDatabaseValues = listOf(SUCCEEDED.name, "SUCCESS")

        val terminalDatabaseValues = successfulDatabaseValues + listOf(
            FAILED.name,
            CANCELLED.name,
            EXPIRED.name,
            PARTIALLY_REFUNDED.name,
            REFUNDED.name
        )
    }
}

enum class PaymentCompensationStatus {
    PENDING_REVIEW,
    PROCESSING,
    SUCCEEDED,
    FAILED
}

/** Provider-confirmed charges that must be refunded outside the order refund lifecycle. */
object PaymentCompensation {
    const val DUPLICATE_PAYMENT_SUCCEEDED = "DUPLICATE_PAYMENT_SUCCEEDED"
    const val PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE = "PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE"
    const val PAYMENT_SUCCEEDED_AMOUNT_MISMATCH = "PAYMENT_SUCCEEDED_AMOUNT_MISMATCH"
    const val PAYMENT_SUCCEEDED_CURRENCY_MISMATCH = "PAYMENT_SUCCEEDED_CURRENCY_MISMATCH"

    private val reasonCodes = setOf(
        DUPLICATE_PAYMENT_SUCCEEDED,
        PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE,
        PAYMENT_SUCCEEDED_AMOUNT_MISMATCH,
        PAYMENT_SUCCEEDED_CURRENCY_MISMATCH
    )

    fun isRequired(failureCode: String?): Boolean = failureCode in reasonCodes
}
