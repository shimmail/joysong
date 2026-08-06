package com.joysong.app.data.remote.dto

data class AgentProfileRequestDto(
    val city: String,
    val goals: List<String>,
    val budgetMin: Double? = null,
    val budgetMax: Double?,
    val acceptableDowntimeDays: Int?,
    val painTolerance: String,
    val preferences: List<String> = emptyList(),
    val excludedProjects: List<String> = emptyList(),
    val consentVersion: String = "agent-profile-v1"
)

data class AgentProfileDto(
    val id: String? = null,
    val city: String = "",
    val goals: List<String> = emptyList(),
    val budgetMin: Double? = null,
    val budgetMax: Double? = null,
    val acceptableDowntimeDays: Int? = null,
    val painTolerance: String = "",
    val preferences: List<String> = emptyList(),
    val excludedProjects: List<String> = emptyList(),
    val consentVersion: String = "",
    val confirmedAt: String? = null,
    val completenessScore: Int = 0,
    val missingFields: List<String> = emptyList()
)

data class AgentSafetyScreeningRequestDto(
    val pregnantOrNursing: Boolean = false,
    val activeSkinCondition: Boolean = false,
    val severeAllergyHistory: Boolean = false,
    val takingRelevantMedication: Boolean = false,
    val recentProcedure: Boolean = false,
    val expectsGuaranteedResult: Boolean = false,
    val severeDistressAboutAppearance: Boolean = false
)

data class CreateAgentAssessmentRequestDto(
    val screening: AgentSafetyScreeningRequestDto
)

data class AgentAssessmentDto(
    val id: String = "",
    val status: String = "",
    val completenessScore: Int = 0,
    val riskLevel: String = "NONE",
    val riskReasons: List<String> = emptyList(),
    val missingFields: List<String> = emptyList(),
    val nextAction: String = ""
)

data class AgentPlanItemDto(
    val id: String = "",
    val stage: String = "",
    val projectId: String? = null,
    val projectName: String = "",
    val recommendationType: String = "",
    val reason: String = "",
    val expectedBenefit: String = "",
    val limitations: String = "",
    val risks: List<String> = emptyList(),
    val alternatives: List<String> = emptyList(),
    val requiredConfirmations: List<String> = emptyList(),
    val confidence: String = "LOW"
)

data class AgentPlanDto(
    val id: String = "",
    val assessmentId: String = "",
    val version: Int = 0,
    val status: String = "",
    val summary: String = "",
    val totalBudgetMin: Double? = null,
    val totalBudgetMax: Double? = null,
    val items: List<AgentPlanItemDto> = emptyList(),
    val createdAt: String = ""
)

data class AgentCatalogReportRequestDto(val query: String, val mode: String = "AUTO")

data class AgentCatalogItemDto(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val subtitle: String = "",
    val summary: String = "",
    val attributes: Map<String, String> = emptyMap(),
    val institutionId: String? = null,
    val projectId: String? = null,
    val canChatWithHuman: Boolean = false
)

data class AgentCatalogReportDto(
    val mode: String = "SUMMARY",
    val title: String = "",
    val summary: String = "",
    val items: List<AgentCatalogItemDto> = emptyList(),
    val comparisonDimensions: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)
