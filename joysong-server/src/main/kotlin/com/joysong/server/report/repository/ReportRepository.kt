package com.joysong.server.report.repository

import com.joysong.server.report.entity.ReportEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ReportRepository : JpaRepository<ReportEntity, String> {
    fun findByTargetTypeAndTargetId(targetType: String, targetId: String): List<ReportEntity>
    fun findByStatusAndDeletedFalse(status: String): List<ReportEntity>
    fun existsByUserIdAndTargetTypeAndTargetId(userId: String, targetType: String, targetId: String): Boolean
    fun findAllByDeletedFalseOrderByCreatedAtDesc(): List<ReportEntity>
    fun findAllByDeletedTrueOrderByCreatedAtDesc(): List<ReportEntity>
    fun findAllByOrderByCreatedAtDesc(): List<ReportEntity>
}
