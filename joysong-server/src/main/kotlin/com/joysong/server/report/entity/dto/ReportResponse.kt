package com.joysong.server.report.entity.dto

data class ReportResponse(
    val id: String,
    val userId: String,
    val targetType: String,
    val targetId: String,
    val reason: String,
    val description: String?,
    val status: String,
    val createdAt: String
)
