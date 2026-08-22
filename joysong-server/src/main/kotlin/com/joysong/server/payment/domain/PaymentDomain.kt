package com.joysong.server.payment.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

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

object Money {
    private val MAX_USD_AMOUNT = BigDecimal("99999999.99")

    fun normalizeCurrency(currency: String): String {
        val normalized = currency.trim().uppercase()
        require(normalized.length == 3) { "INVALID_CURRENCY" }
        try {
            Currency.getInstance(normalized)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("INVALID_CURRENCY")
        }
        return normalized
    }

    fun toMinor(amount: BigDecimal, currency: String): Long {
        require(amount >= BigDecimal.ZERO) { "INVALID_PAYMENT_AMOUNT" }
        val normalized = normalizeCurrency(currency)
        val fractionDigits = Currency.getInstance(normalized).defaultFractionDigits
        require(fractionDigits >= 0) { "UNSUPPORTED_CURRENCY" }
        return try {
            amount.movePointRight(fractionDigits)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("INVALID_PAYMENT_AMOUNT_PRECISION")
        }
    }

    /** Shared write-side validation for USD configuration values that will later be snapshotted to minor units. */
    fun requireUsdAmount(value: BigDecimal) {
        require(
            value >= BigDecimal.ZERO &&
                value <= MAX_USD_AMOUNT &&
                value.stripTrailingZeros().scale() <= 2
        ) { "金额须在范围内且最多两位小数" }
    }

    fun fromMinor(amountMinor: Long, currency: String): BigDecimal {
        require(amountMinor >= 0) { "INVALID_PAYMENT_AMOUNT" }
        val normalized = normalizeCurrency(currency)
        val fractionDigits = Currency.getInstance(normalized).defaultFractionDigits
        require(fractionDigits >= 0) { "UNSUPPORTED_CURRENCY" }
        return BigDecimal.valueOf(amountMinor, fractionDigits)
    }
}
