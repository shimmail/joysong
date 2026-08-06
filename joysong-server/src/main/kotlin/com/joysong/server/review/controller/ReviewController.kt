package com.joysong.server.review.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.review.dto.ReviewResponse
import com.joysong.server.review.service.ReviewService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

data class UpdateReviewRequest(
    val rating: Int,
    val content: String,
    val tags: String = "",
    val images: String = ""
)

@RestController
@RequestMapping("/api/reviews")
class ReviewController(private val reviewService: ReviewService) {

    @GetMapping("/order/{orderId}")
    fun getReviewByOrder(@PathVariable orderId: String, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        val review = reviewService.getReviewByOrderId(orderId, userId)
            ?: return BaseResponse<Any>(code = 404, message = "评价不存在")
        return BaseResponse.success(ReviewResponse.from(review))
    }

    @PutMapping("/{id}")
    fun updateReview(
        @PathVariable id: String,
        @RequestBody request: UpdateReviewRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val review = reviewService.updateReview(
                reviewId = id,
                userId = userId,
                rating = request.rating,
                content = request.content,
                tags = request.tags,
                images = request.images
            )
            BaseResponse.success(ReviewResponse.from(review))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "更新评价失败")
        }
    }

    @DeleteMapping("/{id}")
    fun deleteReview(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            reviewService.deleteUserReview(id, userId)
            BaseResponse.success(mapOf("code" to 200, "message" to "ok"))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "删除评价失败")
        }
    }
}
