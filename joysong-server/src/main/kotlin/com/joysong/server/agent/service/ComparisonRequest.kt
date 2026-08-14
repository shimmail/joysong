package com.joysong.server.agent.service

import com.fasterxml.jackson.annotation.JsonIgnore

data class ComparisonOperand(
    val entityType: AgentQueryTarget,
    val entityId: String,
    val displayName: String
)

enum class ComparisonMissingField { OPERANDS, TARGET_TYPE }

data class ComparisonRequest(
    val operands: List<ComparisonOperand> = emptyList(),
    val targetType: AgentQueryTarget? = null,
    val dimensions: List<String> = emptyList(),
    val constraints: Map<String, String> = emptyMap(),
    val missingFields: Set<ComparisonMissingField> = emptySet()
) {
    @get:JsonIgnore
    val isComplete: Boolean get() = missingFields.isEmpty()
}
