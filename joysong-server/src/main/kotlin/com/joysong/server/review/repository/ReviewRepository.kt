package com.joysong.server.review.repository

import com.joysong.server.review.entity.ReviewEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ReviewRepository : JpaRepository<ReviewEntity, String> {
    fun findByOrderId(orderId: String): Optional<ReviewEntity>
    fun findByOrderIdAndTargetType(orderId: String, targetType: String): Optional<ReviewEntity>
    fun findByUserId(userId: String): List<ReviewEntity>
    fun findByTargetTypeAndTargetId(targetType: String, targetId: String): List<ReviewEntity>
    fun findByDoctorId(doctorId: String): List<ReviewEntity>
    fun findByDoctorIdAndTargetType(doctorId: String, targetType: String): List<ReviewEntity>

    @Query(
        """
        SELECT r
        FROM ReviewEntity r, OrderEntity o
        WHERE r.orderId = o.id
          AND o.institutionProjectId = :institutionProjectId
          AND r.targetType = 'INSTITUTION'
        """
    )
    fun findByInstitutionProjectId(
        @Param("institutionProjectId") institutionProjectId: String
    ): List<ReviewEntity>

    @Query("SELECT r FROM ReviewEntity r WHERE r.targetType = 'INSTITUTION' AND r.targetId = :institutionId")
    fun findByInstitutionId(@Param("institutionId") institutionId: String): List<ReviewEntity>
}
