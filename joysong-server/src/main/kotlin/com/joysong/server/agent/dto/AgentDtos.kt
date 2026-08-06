package com.joysong.server.agent.dto

import java.math.BigDecimal

data class AgentProfileRequest(
    val city: String = "",
    val goals: List<String> = emptyList(),
    val budgetMin: BigDecimal? = null,
    val budgetMax: BigDecimal? = null,
    val acceptableDowntimeDays: Int? = null,
    val painTolerance: String = "",
    val preferences: List<String> = emptyList(),
    val excludedProjects: List<String> = emptyList(),
    val consentVersion: String = ""
)

data class AgentProfileResponse(
    val id: String?,
    val city: String,
    val goals: List<String>,
    val budgetMin: BigDecimal?,
    val budgetMax: BigDecimal?,
    val acceptableDowntimeDays: Int?,
    val painTolerance: String,
    val preferences: List<String>,
    val excludedProjects: List<String>,
    val consentVersion: String,
    val confirmedAt: String?,
    val completenessScore: Int,
    val missingFields: List<String>
)

data class SafetyScreeningRequest(
    val pregnantOrNursing: Boolean = false,
    val activeSkinCondition: Boolean = false,
    val severeAllergyHistory: Boolean = false,
    val takingRelevantMedication: Boolean = false,
    val recentProcedure: Boolean = false,
    val expectsGuaranteedResult: Boolean = false,
    val severeDistressAboutAppearance: Boolean = false
)

data class CreateAssessmentRequest(
    val screening: SafetyScreeningRequest = SafetyScreeningRequest()
)

data class AgentAssessmentResponse(
    val id: String,
    val status: String,
    val completenessScore: Int,
    val riskLevel: String,
    val riskReasons: List<String>,
    val missingFields: List<String>,
    val nextAction: String
)

data class AgentPlanItemResponse(
    val id: String,
    val stage: String,
    val projectId: String?,
    val projectName: String,
    val recommendationType: String,
    val reason: String,
    val expectedBenefit: String,
    val limitations: String,
    val risks: List<String>,
    val alternatives: List<String>,
    val requiredConfirmations: List<String>,
    val confidence: String
)

data class AgentPlanResponse(
    val id: String,
    val assessmentId: String,
    val version: Int,
    val status: String,
    val summary: String,
    val totalBudgetMin: BigDecimal?,
    val totalBudgetMax: BigDecimal?,
    val items: List<AgentPlanItemResponse>,
    val createdAt: String
)

data class AgentCatalogReportRequest(
    val query: String = "",
    val mode: String = "AUTO"
)

data class AgentCatalogItemResponse(
    val type: String,
    val id: String,
    val name: String,
    val subtitle: String,
    val summary: String,
    val attributes: Map<String, String>,
    val institutionId: String? = null,
    val projectId: String? = null,
    val canChatWithHuman: Boolean = false
)

data class AgentCatalogReportResponse(
    val mode: String,
    val title: String,
    val summary: String,
    val items: List<AgentCatalogItemResponse>,
    val comparisonDimensions: List<String>,
    val warnings: List<String>
)
