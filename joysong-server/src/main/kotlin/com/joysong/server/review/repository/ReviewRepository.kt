package com.joysong.server.review.repository

import com.joysong.server.review.entity.ReviewEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ReviewRepository : JpaRepository<ReviewEntity, String> {
    fun findByOrderId(orderId: String): Optional<ReviewEntity>
    fun findByUserId(userId: String): List<ReviewEntity>
    fun findByTargetTypeAndTargetId(targetType: String, targetId: String): List<ReviewEntity>
    fun findByDoctorId(doctorId: String): List<ReviewEntity>

    @Query("SELECT r FROM ReviewEntity r WHERE r.targetType = 'INSTITUTION' AND r.targetId = :institutionId")
    fun findByInstitutionId(@Param("institutionId") institutionId: String): List<ReviewEntity>
}
