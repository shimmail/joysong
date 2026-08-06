package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.CreateOrderRequestDto
import com.joysong.app.data.remote.dto.PayOrderRequestDto
import com.joysong.app.data.remote.dto.ReviewOrderRequestDto
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.Payment
import com.joysong.app.domain.model.Review
import com.joysong.app.domain.model.Settlement
import com.joysong.app.domain.repository.OrderRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : OrderRepository {

    override suspend fun createOrder(
        projectId: String,
        institutionProjectId: String?,
        doctorId: String,
        quantity: Int,
        remark: String,
        userCouponId: Long?,
        appointmentTime: String?
    ): Result<Order> {
        return try {
            val response = apiService.createOrder(
                CreateOrderRequestDto(
                    projectId = projectId,
                    institutionProjectId = institutionProjectId,
                    doctorId = doctorId,
                    quantity = quantity,
                    remark = remark,
                    userCouponId = userCouponId,
                    appointmentTime = appointmentTime
                )
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrders(): Result<List<Order>> {
        return try {
            val response = apiService.getOrders()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrderById(id: String): Result<Order> {
        return try {
            val response = apiService.getOrderById(id)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun payOrder(id: String, method: String): Result<Payment> {
        return try {
            val response = apiService.payOrder(id, PayOrderRequestDto(method))
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelOrder(id: String): Result<String> {
        return try {
            val response = apiService.cancelOrder(id)
            if (response.code == 200) {
                Result.success(response.data ?: "OK")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun submitReview(
        orderId: String,
        rating: Int,
        content: String,
        images: String,
        targetId: String,
        targetType: String,
        doctorId: String
    ): Result<Unit> {
        return try {
            val response = apiService.reviewOrder(
                orderId,
                ReviewOrderRequestDto(
                    rating = rating,
                    content = content,
                    images = images,
                    targetId = targetId,
                    targetType = targetType,
                    doctorId = doctorId
                )
            )
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun payConsultationFee(orderId: String): Result<Order> {
        return try {
            val response = apiService.payConsultationFee(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun payBalance(orderId: String): Result<Order> {
        return try {
            val response = apiService.payBalance(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun verifyOrder(orderId: String): Result<Order> {
        return try {
            val response = apiService.verifyOrder(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun confirmCompletion(orderId: String): Result<Order> {
        return try {
            val response = apiService.confirmCompletion(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getSettlement(orderId: String): Result<Settlement> {
        return try {
            val response = apiService.getSettlement(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun getStatusLogs(orderId: String): Result<List<Map<String, Any>>> {
        return try {
            val response = apiService.getStatusLogs(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun deleteOrder(id: String): Result<String> {
        return try {
            val response = apiService.deleteOrder(id)
            if (response.code == 200) Result.success(response.data ?: "OK")
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getReviewByOrderId(orderId: String): Result<Review> {
        return try {
            val response = apiService.getReviewByOrder(orderId)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message ?: "Failed to load review"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateReview(reviewId: String, rating: Int, content: String, tags: String, images: String): Result<Review> {
        return try {
            val request = ReviewOrderRequestDto(
                rating = rating,
                content = content,
                tags = tags,
                images = images
            )
            val response = apiService.updateReview(reviewId, request)
            if (response.code == 200 && response.data != null) Result.success(response.data.toDomain())
            else Result.failure(Exception(response.message ?: "Failed to update review"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteReview(reviewId: String): Result<Unit> {
        return try {
            val response = apiService.deleteReview(reviewId)
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message ?: "Failed to delete review"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
