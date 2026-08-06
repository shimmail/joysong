package com.joysong.app.domain.repository

import com.joysong.app.domain.model.LikeResponse

interface LikeRepository {
    suspend fun like(targetType: String, targetId: String): Result<String>
    suspend fun unlike(targetType: String, targetId: String): Result<String>
    suspend fun isLiked(targetType: String, targetId: String): Result<LikeResponse>
}
