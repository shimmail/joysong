package com.joysong.server.like.repository

import com.joysong.server.like.entity.LikeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface LikeRepository : JpaRepository<LikeEntity, String> {
    fun findByUserIdAndTargetTypeAndTargetId(userId: String, targetType: String, targetId: String): LikeEntity?
    fun countByTargetTypeAndTargetId(targetType: String, targetId: String): Long
    fun existsByUserIdAndTargetTypeAndTargetId(userId: String, targetType: String, targetId: String): Boolean
}
