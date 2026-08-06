package com.joysong.app.domain.model

enum class RefundStatus {
    PENDING, APPROVED, REJECTED
}

data class Refund(
    val id: String,
    val orderId: String,
    val amount: Double,
    val reason: String,
    val description: String = "",
    val status: RefundStatus,
    val createdAt: String = "",
    val processedAt: Long = 0L
)
