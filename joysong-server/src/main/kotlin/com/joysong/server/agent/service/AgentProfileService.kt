package com.joysong.server.agent.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentProfileRequest
import com.joysong.server.agent.dto.AgentProfileResponse
import com.joysong.server.agent.entity.AgentUserProfileEntity
import com.joysong.server.agent.repository.AgentUserProfileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID
import com.joysong.server.user.service.AccountLifecycleGuard

@Service
class AgentProfileService(
    private val repository: AgentUserProfileRepository,
    private val objectMapper: ObjectMapper,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {
    fun get(userId: String): AgentProfileResponse {
        val profile = repository.findByUserId(userId)
        return profile?.toResponse() ?: emptyResponse()
    }

    @Transactional
    fun upsert(userId: String, request: AgentProfileRequest): AgentProfileResponse {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        require(request.city.trim().length <= 100) { AgentText.value("城市名称过长", "City is too long") }
        require(request.painTolerance.trim().length <= 20) { AgentText.value("疼痛耐受值过长", "Pain tolerance is too long") }
        require(request.consentVersion.trim().length <= 50) { AgentText.value("授权版本号过长", "Consent version is too long") }
        validateList("goals", request.goals)
        validateList("preferences", request.preferences)
        validateList("excludedProjects", request.excludedProjects)
        require(request.budgetMin == null || request.budgetMin.signum() >= 0) { AgentText.value("最低预算不能小于0", "Minimum budget cannot be negative") }
        require(request.budgetMax == null || request.budgetMax.signum() >= 0) { AgentText.value("最高预算不能小于0", "Maximum budget cannot be negative") }
        val maximumBudget = BigDecimal("99999999.99")
        require(request.budgetMin == null || request.budgetMin <= maximumBudget) { AgentText.value("最低预算超出范围", "Minimum budget is too large") }
        require(request.budgetMax == null || request.budgetMax <= maximumBudget) { AgentText.value("最高预算超出范围", "Maximum budget is too large") }
        require(request.budgetMin == null || request.budgetMax == null || request.budgetMin <= request.budgetMax) {
            AgentText.value("最低预算不能高于最高预算", "Minimum budget cannot exceed maximum budget")
        }
        require(request.acceptableDowntimeDays == null || request.acceptableDowntimeDays in 0..365) { AgentText.value("恢复期范围无效", "Downtime must be between 0 and 365 days") }

        val profile = repository.findByUserId(userId) ?: AgentUserProfileEntity(
            id = UUID.randomUUID().toString(),
            userId = userId
        )
        profile.city = request.city.trim()
        profile.goalsJson = objectMapper.writeValueAsString(request.goals.clean())
        profile.budgetMin = request.budgetMin
        profile.budgetMax = request.budgetMax
        profile.acceptableDowntimeDays = request.acceptableDowntimeDays
        profile.painTolerance = request.painTolerance.trim().uppercase()
        profile.preferencesJson = objectMapper.writeValueAsString(request.preferences.clean())
        profile.excludedProjectsJson = objectMapper.writeValueAsString(request.excludedProjects.clean())
        profile.consentVersion = request.consentVersion.trim()
        profile.confirmedAt = null
        profile.updatedAt = LocalDateTime.now()
        return repository.save(profile).toResponse()
    }

    @Transactional
    fun confirm(userId: String): AgentProfileResponse {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val profile = repository.findByUserId(userId) ?: throw IllegalArgumentException(AgentText.value("请先填写需求档案", "Complete your needs profile first"))
        require(profile.consentVersion.isNotBlank()) { AgentText.value("请先确认隐私授权", "Confirm profile data permission first") }
        profile.confirmedAt = LocalDateTime.now()
        profile.updatedAt = LocalDateTime.now()
        return repository.save(profile).toResponse()
    }

    fun requireEntity(userId: String): AgentUserProfileEntity =
        repository.findByUserId(userId) ?: throw IllegalArgumentException(AgentText.value("请先填写需求档案", "Complete your needs profile first"))

    fun missingFields(profile: AgentUserProfileEntity): List<String> {
        val missing = mutableListOf<String>()
        if (readList(profile.goalsJson).isEmpty()) missing += AgentText.field("goals")
        if (profile.city.isBlank()) missing += AgentText.field("city")
        if (profile.budgetMax == null) missing += AgentText.field("budget")
        if (profile.acceptableDowntimeDays == null) missing += AgentText.field("downtime")
        if (profile.painTolerance.isBlank()) missing += AgentText.field("pain")
        return missing
    }

    fun completeness(profile: AgentUserProfileEntity): Int = 100 - missingFields(profile).size * 20

    fun readList(json: String): List<String> = runCatching {
        objectMapper.readValue(json, object : TypeReference<List<String>>() {})
    }.getOrDefault(emptyList())

    private fun AgentUserProfileEntity.toResponse(): AgentProfileResponse = AgentProfileResponse(
        id = id,
        city = city,
        goals = readList(goalsJson),
        budgetMin = budgetMin,
        budgetMax = budgetMax,
        acceptableDowntimeDays = acceptableDowntimeDays,
        painTolerance = painTolerance,
        preferences = readList(preferencesJson),
        excludedProjects = readList(excludedProjectsJson),
        consentVersion = consentVersion,
        confirmedAt = confirmedAt?.toString(),
        completenessScore = completeness(this),
        missingFields = missingFields(this)
    )

    private fun emptyResponse() = AgentProfileResponse(
        id = null, city = "", goals = emptyList(), budgetMin = null, budgetMax = null,
        acceptableDowntimeDays = null, painTolerance = "", preferences = emptyList(),
        excludedProjects = emptyList(), consentVersion = "", confirmedAt = null,
        completenessScore = 0,
        missingFields = listOf("goals", "city", "budget", "downtime", "pain").map(AgentText::field)
    )

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotBlank() }.distinct().take(20)

    private fun validateList(field: String, values: List<String>) {
        require(values.size <= 20) { "$field 最多包含 20 项" }
        require(values.all { it.trim().length <= 100 }) { "$field 单项不能超过 100 字" }
    }
}
