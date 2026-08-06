package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.PublishDiaryRequestDto
import com.joysong.app.data.remote.dto.UpdateDiaryRequestDto
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.repository.DiaryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiaryRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : DiaryRepository {

    override suspend fun getMyDiaries(): Result<List<Diary>> {
        return try {
            val response = apiService.getMyDiaries()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun publishDiary(
        title: String,
        content: String,
        images: List<String>,
        tags: List<String>,
        rating: Int,
        doctorId: String,
        projectId: String,
        institutionId: String,
        institutionProjectId: String,
        orderId: String,
        beforeImages: List<String>,
        afterImages: List<String>,
        status: String
    ): Result<Diary> {
        return try {
            val response = apiService.publishDiary(
                PublishDiaryRequestDto(
                    title = title,
                    content = content,
                    images = images.joinToString(","),
                    tags = tags.joinToString(","),
                    rating = rating,
                    doctorId = doctorId,
                    projectId = projectId,
                    institutionId = institutionId,
                    institutionProjectId = institutionProjectId,
                    orderId = orderId,
                    beforeImages = beforeImages.joinToString(","),
                    afterImages = afterImages.joinToString(","),
                    status = status
                )
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateDiary(
        id: String,
        title: String?,
        content: String?,
        images: String?,
        tags: String?,
        rating: Int?,
        doctorId: String?,
        projectId: String?,
        institutionId: String?,
        institutionProjectId: String?,
        orderId: String?,
        beforeImages: String?,
        afterImages: String?,
        status: String?
    ): Result<Diary> {
        return try {
            val response = apiService.updateDiary(
                id,
                UpdateDiaryRequestDto(
                    title = title,
                    content = content,
                    images = images,
                    tags = tags,
                    rating = rating,
                    doctorId = doctorId,
                    projectId = projectId,
                    institutionId = institutionId,
                    institutionProjectId = institutionProjectId,
                    orderId = orderId,
                    beforeImages = beforeImages,
                    afterImages = afterImages,
                    status = status
                )
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteDiary(id: String): Result<String> {
        return try {
            val response = apiService.deleteDiary(id)
            if (response.code == 200) {
                Result.success(response.data ?: "deleted")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
