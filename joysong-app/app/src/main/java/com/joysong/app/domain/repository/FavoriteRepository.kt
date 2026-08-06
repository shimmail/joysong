package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Favorite
import com.joysong.app.domain.model.FavoriteStatus
import com.joysong.app.domain.model.FavoriteType

interface FavoriteRepository {
    suspend fun getFavorites(): Result<List<Favorite>>
    suspend fun addFavorite(type: FavoriteType, targetId: String, targetName: String = "", targetImage: String = ""): Result<Unit>
    suspend fun removeFavorite(type: FavoriteType, targetId: String): Result<Unit>
    suspend fun isFavorite(type: FavoriteType, targetId: String): Result<FavoriteStatus>
}
