package com.joysong.server.common.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

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
