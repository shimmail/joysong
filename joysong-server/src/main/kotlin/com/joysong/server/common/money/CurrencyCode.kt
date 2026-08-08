package com.joysong.server.common.money

enum class CurrencyCode {
    USD,
    CNY;

    companion object {
        val DEFAULT: CurrencyCode = USD
        const val DEFAULT_CODE: String = "USD"
    }
}
