package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Diary

interface DiaryRepository {
    suspend fun getMyDiaries(): Result<List<Diary>>
    suspend fun publishDiary(
        title: String,
        content: String,
        images: List<String>,
        tags: List<String>,
        rating: Int = 0,
        doctorId: String = "",
        projectId: String = "",
        institutionId: String = "",
        institutionProjectId: String = "",
        orderId: String = "",
        beforeImages: List<String> = emptyList(),
        afterImages: List<String> = emptyList(),
        status: String = "published"
    ): Result<Diary>
    suspend fun updateDiary(
        id: String,
        title: String? = null,
        content: String? = null,
        images: String? = null,
        tags: String? = null,
        rating: Int? = null,
        doctorId: String? = null,
        projectId: String? = null,
        institutionId: String? = null,
        institutionProjectId: String? = null,
        orderId: String? = null,
        beforeImages: String? = null,
        afterImages: String? = null,
        status: String? = null
    ): Result<Diary>
    suspend fun deleteDiary(id: String): Result<String>
}
