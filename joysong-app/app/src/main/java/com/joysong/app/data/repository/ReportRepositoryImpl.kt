package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.ReportRequestDto
import com.joysong.app.domain.repository.ReportRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : ReportRepository {

    override suspend fun submitReport(
        targetType: String,
        targetId: String,
        reason: String,
        description: String?
    ): Result<String> {
        return try {
            val response = apiService.submitReport(
                ReportRequestDto(
                    targetType = targetType,
                    targetId = targetId,
                    reason = reason,
                    description = description
                )
            )
            if (response.code == 200) {
                Result.success("举报已提交")
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun checkReported(targetType: String, targetId: String): Result<Boolean> {
        return try {
            val response = apiService.checkReported(targetType, targetId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.reported)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
