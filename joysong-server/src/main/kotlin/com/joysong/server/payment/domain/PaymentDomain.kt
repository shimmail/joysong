package com.joysong.server.payment.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

enum class PaymentProvider {
    DEMO,
    STRIPE,
    PAYPAL,
    WECHAT_PAY,
    ALIPAY;

    companion object {
        fun parse(value: String): PaymentProvider =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("UNSUPPORTED_PAYMENT_PROVIDER")
    }
}

enum class PaymentType {
    CONSULTATION_FEE,
    BALANCE
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
    }
}

object Money {
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

    fun fromMinor(amountMinor: Long, currency: String): BigDecimal {
        require(amountMinor >= 0) { "INVALID_PAYMENT_AMOUNT" }
        val normalized = normalizeCurrency(currency)
        val fractionDigits = Currency.getInstance(normalized).defaultFractionDigits
        require(fractionDigits >= 0) { "UNSUPPORTED_CURRENCY" }
        return BigDecimal.valueOf(amountMinor, fractionDigits)
    }
}
