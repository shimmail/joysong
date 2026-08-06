package com.joysong.app.domain.model

enum class PaymentStatus {
    PENDING, SUCCESS, FAILED
}

data class Payment(
    val id: String,
    val orderId: String,
    val amount: Double,
    val method: String,
    val status: PaymentStatus,
    val paidAt: Long = 0L,
    val transactionId: String = ""
)
