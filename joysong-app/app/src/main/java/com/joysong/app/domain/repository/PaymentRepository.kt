package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Payment
import com.joysong.app.domain.model.Refund
import com.joysong.app.domain.model.Review

interface PaymentRepository {
    suspend fun payOrder(orderId: String, method: String): Result<Payment>
    suspend fun refundOrder(orderId: String, reason: String, description: String, evidenceUrl: String = ""): Result<Refund>
    suspend fun submitReview(
        orderId: String,
        rating: Int,
        content: String,
        tags: List<String>,
        images: List<String>
    ): Result<Review>
    suspend fun cancelRefund(orderId: String): Result<String>
}
