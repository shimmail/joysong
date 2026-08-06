package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.AddFavoriteRequestDto
import com.joysong.app.domain.model.Favorite
import com.joysong.app.domain.model.FavoriteStatus
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.repository.FavoriteRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : FavoriteRepository {

    override suspend fun getFavorites(): Result<List<Favorite>> {
        return try {
            val response = apiService.getFavorites()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addFavorite(type: FavoriteType, targetId: String, targetName: String, targetImage: String): Result<Unit> {
        return try {
            val response = apiService.addFavorite(
                AddFavoriteRequestDto(type.name, targetId, targetName, targetImage)
            )
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeFavorite(type: FavoriteType, targetId: String): Result<Unit> {
        return try {
            val response = apiService.removeFavorite(type.name, targetId)
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isFavorite(type: FavoriteType, targetId: String): Result<FavoriteStatus> {
        return try {
            val response = apiService.isFavorite(type.name, targetId)
            if (response.code == 200 && response.data != null) {
                val dto = response.data
                Result.success(FavoriteStatus(favorited = dto.favorited, count = dto.count))
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
