package com.joysong.server.agent.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentAssessmentResponse
import com.joysong.server.agent.dto.CreateAssessmentRequest
import com.joysong.server.agent.entity.AgentAssessmentEntity
import com.joysong.server.agent.repository.AgentAssessmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AgentAssessmentService(
    private val repository: AgentAssessmentRepository,
    private val profileService: AgentProfileService,
    private val safetyService: AgentSafetyService,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun create(userId: String, request: CreateAssessmentRequest): AgentAssessmentResponse {
        val profile = profileService.requireEntity(userId)
        val missing = profileService.missingFields(profile)
        val id = UUID.randomUUID().toString()
        val safety = safetyService.evaluate(userId, id, request.screening)
        val status = when {
            profile.confirmedAt == null -> "NEEDS_PROFILE_CONFIRMATION"
            safety.level == "HIGH" -> "BLOCKED_FOR_SAFETY"
            safety.level == "MEDIUM" -> "NEEDS_CLARIFICATION"
            else -> "READY_FOR_PLANNING"
        }
        val assessment = AgentAssessmentEntity(
            id = id,
            userId = userId,
            status = status,
            completenessScore = profileService.completeness(profile),
            goalSnapshotJson = profile.goalsJson,
            riskLevel = safety.level,
            // Risk details are returned for this turn but are not persisted in plaintext.
            riskReasonsJson = "[]",
            missingFieldsJson = objectMapper.writeValueAsString(missing)
        )
        repository.save(assessment)
        return AgentAssessmentResponse(
            id = assessment.id,
            status = assessment.status,
            completenessScore = assessment.completenessScore,
            riskLevel = safety.level,
            riskReasons = safety.reasons,
            missingFields = missing,
            nextAction = nextAction(status)
        )
    }

    fun getEntity(id: String, userId: String): AgentAssessmentEntity =
        repository.findByIdAndUserId(id, userId) ?: throw IllegalArgumentException(AgentText.value("评估不存在", "Assessment not found"))

    fun AgentAssessmentEntity.toResponse(): AgentAssessmentResponse {
        val riskReasons = readList(riskReasonsJson)
        val missing = readList(missingFieldsJson)
        return AgentAssessmentResponse(
            id = id,
            status = status,
            completenessScore = completenessScore,
            riskLevel = riskLevel,
            riskReasons = riskReasons,
            missingFields = missing,
            nextAction = nextAction(status)
        )
    }

    private fun nextAction(status: String): String = when (status) {
        "READY_FOR_PLANNING" -> "CREATE_PLAN"
        "BLOCKED_FOR_SAFETY" -> "HUMAN_REVIEW"
        "NEEDS_PROFILE_CONFIRMATION" -> "CONFIRM_PROFILE"
        else -> "COMPLETE_PROFILE"
    }

    private fun readList(json: String): List<String> = runCatching {
        objectMapper.readValue(json, object : TypeReference<List<String>>() {})
    }.getOrDefault(emptyList())
}
