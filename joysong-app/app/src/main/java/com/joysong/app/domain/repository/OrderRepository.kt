package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.Payment
import com.joysong.app.domain.model.Review
import com.joysong.app.domain.model.Settlement

interface OrderRepository {
    suspend fun createOrder(
        projectId: String,
        institutionProjectId: String? = null,
        doctorId: String = "",
        quantity: Int = 1,
        remark: String = "",
        userCouponId: Long? = null,
        appointmentTime: String? = null
    ): Result<Order>
    suspend fun getOrders(): Result<List<Order>>
    suspend fun getOrderById(id: String): Result<Order>
    suspend fun payOrder(id: String, method: String): Result<Payment>
    suspend fun cancelOrder(id: String): Result<String>
    suspend fun submitReview(
        orderId: String,
        rating: Int,
        content: String,
        images: String,
        targetId: String,
        targetType: String,
        doctorId: String
    ): Result<Unit>
    suspend fun payConsultationFee(orderId: String): Result<Order>
    suspend fun payBalance(orderId: String): Result<Order>
    suspend fun verifyOrder(orderId: String): Result<Order>
    suspend fun confirmCompletion(orderId: String): Result<Order>
    suspend fun getSettlement(orderId: String): Result<Settlement>
    suspend fun getStatusLogs(orderId: String): Result<List<Map<String, Any>>>
    suspend fun deleteOrder(id: String): Result<String>
    suspend fun getReviewByOrderId(orderId: String): Result<Review>
    suspend fun updateReview(reviewId: String, rating: Int, content: String, tags: String, images: String): Result<Review>
    suspend fun deleteReview(reviewId: String): Result<Unit>
}
