package com.joysong.server.refund.domain

enum class RefundReasonCode {
    CUSTOMER_REQUEST,
    DUPLICATE_PAYMENT,
    FRAUD_SUSPECTED,
    SERVICE_NOT_PROVIDED,
    SERVICE_NOT_AS_DESCRIBED,
    ORDER_CANCELLED,
    BALANCE_PAYMENT_TIMEOUT,
    CONSULTATION_NO_SHOW_TIMEOUT,
    OTHER;

    companion object {
        fun normalize(value: String?): RefundReasonCode {
            if (value.isNullOrBlank()) return OTHER
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("INVALID_REFUND_REASON_CODE")
        }
    }
}
