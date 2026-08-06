package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.review.dto.ReviewResponse
import com.joysong.server.review.service.ReviewService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminReviewController(
    private val reviewService: ReviewService
) {

    @GetMapping("/reviews")
    fun listReviews(): BaseResponse<*> =
        BaseResponse.success(reviewService.adminListAll().map { ReviewResponse.from(it) })

    @DeleteMapping("/reviews/{id}")
    fun deleteReview(@PathVariable id: String): BaseResponse<*> {
        reviewService.adminDeleteById(id)
        return BaseResponse.success(mapOf("code" to 200, "message" to "ok"))
    }
}
