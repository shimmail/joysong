package com.joysong.server.review.dto

import com.joysong.server.review.entity.ReviewEntity
import java.time.format.DateTimeFormatter

data class ReviewResponse(
    val id: String,
    val orderId: String,
    val userId: String,
    val userName: String = "",
    val doctorId: String,
    val rating: Int,
    val content: String,
    val tags: String,
    val images: String,
    val createdAt: String
) {
    companion object {
        private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        fun from(entity: ReviewEntity, userName: String = ""): ReviewResponse = ReviewResponse(
            id = entity.id,
            orderId = entity.orderId,
            userId = entity.userId,
            userName = userName,
            doctorId = entity.doctorId,
            rating = entity.rating,
            content = entity.content,
            tags = entity.tags,
            images = entity.images,
            createdAt = entity.createdAt.format(formatter)
        )
    }
}
