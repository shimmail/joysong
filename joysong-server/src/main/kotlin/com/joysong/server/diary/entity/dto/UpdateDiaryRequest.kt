package com.joysong.server.diary.entity.dto

data class UpdateDiaryRequest(
    val title: String? = null,
    val content: String? = null,
    val images: String? = null,
    val tags: String? = null,
    val rating: Int? = null,
    val doctorId: String? = null,
    val projectId: String? = null,
    val institutionId: String? = null,
    val institutionProjectId: String? = null,
    val orderId: String? = null,
    val beforeImages: String? = null,
    val afterImages: String? = null,
    val status: String? = null
)
