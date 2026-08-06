package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.PayOrderRequestDto
import com.joysong.app.data.remote.dto.RefundOrderRequestDto
import com.joysong.app.data.remote.dto.ReviewOrderRequestDto
import com.joysong.app.domain.model.Payment
import com.joysong.app.domain.model.Refund
import com.joysong.app.domain.model.Review
import com.joysong.app.domain.repository.PaymentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : PaymentRepository {

    override suspend fun payOrder(orderId: String, method: String): Result<Payment> {
        return try {
            val response = apiService.payOrder(orderId, PayOrderRequestDto(method))
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refundOrder(
        orderId: String,
        reason: String,
        description: String,
        evidenceUrl: String
    ): Result<Refund> {
        return try {
            val response = apiService.refundOrder(
                orderId,
                RefundOrderRequestDto(reason, description, evidenceUrl)
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun submitReview(
        orderId: String,
        rating: Int,
        content: String,
        tags: List<String>,
        images: List<String>
    ): Result<Review> {
        return try {
            val response = apiService.reviewOrder(
                orderId,
                ReviewOrderRequestDto(
                    rating = rating,
                    content = content,
                    tags = tags.joinToString(","),
                    images = images.joinToString(",")
                )
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancelRefund(orderId: String): Result<String> {
        return try {
            val response = apiService.cancelRefund(orderId)
            if (response.code == 200) {
                Result.success(response.data ?: "")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
