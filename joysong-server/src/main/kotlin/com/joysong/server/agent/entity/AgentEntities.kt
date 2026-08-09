package com.joysong.server.agent.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "agent_user_profiles")
class AgentUserProfileEntity(
    @Id var id: String,
    @Column(name = "user_id", unique = true) var userId: String,
    var city: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "goals_json", columnDefinition = "json") var goalsJson: String = "[]",
    @Column(name = "budget_min") var budgetMin: BigDecimal? = null,
    @Column(name = "budget_max") var budgetMax: BigDecimal? = null,
    @Column(name = "acceptable_downtime_days") var acceptableDowntimeDays: Int? = null,
    @Column(name = "pain_tolerance") var painTolerance: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferences_json", columnDefinition = "json") var preferencesJson: String = "[]",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "excluded_projects_json", columnDefinition = "json") var excludedProjectsJson: String = "[]",
    @Column(name = "consent_version") var consentVersion: String = "",
    @Column(name = "confirmed_at") var confirmedAt: LocalDateTime? = null,
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") var updatedAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "agent_assessments")
class AgentAssessmentEntity(
    @Id var id: String,
    @Column(name = "user_id") var userId: String,
    var status: String,
    @Column(name = "completeness_score") var completenessScore: Int,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "goal_snapshot_json", columnDefinition = "json") var goalSnapshotJson: String,
    @Column(name = "risk_level") var riskLevel: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_reasons_json", columnDefinition = "json") var riskReasonsJson: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "missing_fields_json", columnDefinition = "json") var missingFieldsJson: String,
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "agent_plans")
class AgentPlanEntity(
    @Id var id: String,
    @Column(name = "user_id") var userId: String,
    @Column(name = "assessment_id") var assessmentId: String,
    var version: Int,
    var status: String,
    @Column(columnDefinition = "TEXT") var summary: String,
    @Column(name = "total_budget_min") var totalBudgetMin: BigDecimal? = null,
    @Column(name = "total_budget_max") var totalBudgetMax: BigDecimal? = null,
    @Column(columnDefinition = "char(3)", length = 3, nullable = false) var currency: String = "CNY",
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)

@Entity
@Table(name = "agent_plan_items")
class AgentPlanItemEntity(
    @Id var id: String,
    @Column(name = "plan_id") var planId: String,
    @Column(name = "stage_name") var stageName: String,
    @Column(name = "project_id") var projectId: String? = null,
    @Column(name = "project_name") var projectName: String,
    @Column(name = "recommendation_type") var recommendationType: String,
    @Column(name = "reason_text", columnDefinition = "TEXT") var reason: String,
    @Column(name = "expected_benefit", columnDefinition = "TEXT") var expectedBenefit: String,
    @Column(name = "limitations_text", columnDefinition = "TEXT") var limitations: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risks_json", columnDefinition = "json") var risksJson: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "alternatives_json", columnDefinition = "json") var alternativesJson: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "required_confirmation_json", columnDefinition = "json") var requiredConfirmationJson: String,
    var confidence: String,
    @Column(name = "sort_order") var sortOrder: Int
)

@Entity
@Table(name = "agent_safety_events")
class AgentSafetyEventEntity(
    @Id var id: String,
    @Column(name = "user_id") var userId: String,
    @Column(name = "assessment_id") var assessmentId: String? = null,
    @Column(name = "risk_type") var riskType: String,
    @Column(name = "risk_code") var riskCode: String = "",
    @Column(name = "risk_level") var riskLevel: String,
    @Column(name = "rule_version") var ruleVersion: String = "",
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "evidence_json", columnDefinition = "json") var evidenceJson: String,
    @Column(name = "agent_action") var agentAction: String,
    @Column(name = "review_status") var reviewStatus: String = "PENDING",
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now()
)

data class AgentToolAuditEntity(
    @Id var id: String,
    @Column(name = "user_id") var userId: String,
    @Column(name = "session_id") var sessionId: String? = null,
    @Column(name = "tool_name") var toolName: String,
    @Column(name = "request_summary", columnDefinition = "TEXT") var requestSummary: String,
    @Column(name = "intent") var intent: String? = null,
    @Column(name = "database_search") var databaseSearch: Boolean = false,
    @Column(name = "detected_keywords") var detectedKeywords: String = "",
    @Column(name = "detected_concerns") var detectedConcerns: String = "",
    @Column(name = "matched_entity_ids", columnDefinition = "TEXT") var matchedEntityIds: String? = null,
    @Column(name = "llm_called") var llmCalled: Boolean = false,
    @Column(name = "model_name") var modelName: String = "",
    @Column(name = "gateway_url") var gatewayUrl: String = "",
    @Column(name = "result_status") var resultStatus: String,
    @Column(name = "duration_ms") var durationMs: Long,
    @Column(name = "total_duration_ms") var totalDurationMs: Long = 0,
    @Column(name = "database_duration_ms") var databaseDurationMs: Long = 0,
    @Column(name = "llm_duration_ms") var llmDurationMs: Long = 0,
    @Column(name = "http_status") var httpStatus: Int? = null,
    @Column(name = "input_tokens") var inputTokens: Int? = null,
    @Column(name = "output_tokens") var outputTokens: Int? = null,
    @Column(name = "fallback_used") var fallbackUsed: Boolean = false,
    @Column(name = "answer_length") var answerLength: Int = 0,
    @Column(name = "error_summary") var errorSummary: String? = null,
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now()
)
