package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.LikeRequest
import com.joysong.app.domain.model.LikeResponse
import com.joysong.app.domain.repository.LikeRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LikeRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : LikeRepository {

    override suspend fun like(targetType: String, targetId: String): Result<String> {
        return try {
            val response = apiService.addLike(LikeRequest(targetType, targetId))
            if (response.code == 200) {
                Result.success(response.data ?: "liked")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unlike(targetType: String, targetId: String): Result<String> {
        return try {
            val response = apiService.removeLike(targetType, targetId)
            if (response.code == 200) {
                Result.success(response.data ?: "unliked")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isLiked(targetType: String, targetId: String): Result<LikeResponse> {
        return try {
            val response = apiService.checkLike(targetType, targetId)
            if (response.code == 200 && response.data != null) {
                Result.success(LikeResponse(liked = response.data.liked, count = response.data.count))
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
