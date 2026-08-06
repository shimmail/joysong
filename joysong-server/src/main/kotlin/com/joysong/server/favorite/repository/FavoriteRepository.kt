package com.joysong.server.favorite.repository

import com.joysong.server.favorite.entity.FavoriteEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface FavoriteRepository : JpaRepository<FavoriteEntity, String> {
    fun findByUserId(userId: String): List<FavoriteEntity>
    fun findByUserIdAndTargetType(userId: String, targetType: String): List<FavoriteEntity>
    fun findByUserIdAndTargetTypeAndTargetId(userId: String, targetType: String, targetId: String): Optional<FavoriteEntity>
    fun deleteByUserIdAndTargetTypeAndTargetId(userId: String, targetType: String, targetId: String)
    fun countByTargetTypeAndTargetId(targetType: String, targetId: String): Long
}
