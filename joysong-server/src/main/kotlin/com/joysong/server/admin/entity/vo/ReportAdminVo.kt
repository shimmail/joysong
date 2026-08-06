package com.joysong.server.admin.entity.vo

import java.time.LocalDateTime

data class ReportAdminVo(
    val id: String,
    val userId: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val description: String?,
    val status: String,
    val createdAt: LocalDateTime,
    val deleted: Boolean,
    val targetSummary: Map<String, Any?>?
)
