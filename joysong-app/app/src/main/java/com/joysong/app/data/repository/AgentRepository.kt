package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiException
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.AgentAssessmentDto
import com.joysong.app.data.remote.dto.AgentPlanDto
import com.joysong.app.data.remote.dto.AgentProfileDto
import com.joysong.app.data.remote.dto.AgentProfileRequestDto
import com.joysong.app.data.remote.dto.AgentSafetyScreeningRequestDto
import com.joysong.app.data.remote.dto.CreateAgentAssessmentRequestDto
import com.joysong.app.data.remote.dto.AgentCatalogReportDto
import com.joysong.app.data.remote.dto.AgentCatalogReportRequestDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun getProfile(): Result<AgentProfileDto> = apiCall { apiService.getAgentProfile() }

    suspend fun updateProfile(request: AgentProfileRequestDto): Result<AgentProfileDto> =
        apiCall { apiService.updateAgentProfile(request) }

    suspend fun confirmProfile(): Result<AgentProfileDto> = apiCall { apiService.confirmAgentProfile() }

    suspend fun createAssessment(screening: AgentSafetyScreeningRequestDto): Result<AgentAssessmentDto> =
        apiCall { apiService.createAgentAssessment(CreateAgentAssessmentRequestDto(screening)) }

    suspend fun createPlan(assessmentId: String): Result<AgentPlanDto> =
        apiCall { apiService.createAgentPlan(assessmentId) }

    suspend fun getPlans(): Result<List<AgentPlanDto>> = apiCall { apiService.getAgentPlans() }

    suspend fun deletePlan(planId: String): Result<String> = apiCall { apiService.deleteAgentPlan(planId) }

    suspend fun clearPlans(): Result<String> = apiCall { apiService.clearAgentPlans() }

    suspend fun createCatalogReport(query: String): Result<AgentCatalogReportDto> =
        apiCall { apiService.createAgentCatalogReport(AgentCatalogReportRequestDto(query)) }

    private suspend fun <T> apiCall(block: suspend () -> com.joysong.app.data.remote.dto.ApiResponse<T>): Result<T> =
        try {
            val response = block()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
}
