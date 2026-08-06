package com.joysong.app.domain.model

data class Review(
    val id: String,
    val orderId: String,
    val userId: String,
    val userName: String = "",
    val doctorId: String = "",
    val rating: Int,
    val content: String,
    val tags: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val createdAt: String = ""
)
