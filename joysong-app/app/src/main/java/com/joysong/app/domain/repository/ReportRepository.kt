package com.joysong.app.domain.repository

interface ReportRepository {
    suspend fun submitReport(targetType: String, targetId: String, reason: String, description: String?): Result<String>
    suspend fun checkReported(targetType: String, targetId: String): Result<Boolean>
}
