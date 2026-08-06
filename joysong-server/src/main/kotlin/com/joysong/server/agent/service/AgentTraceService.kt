package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentToolAuditEntity
import com.joysong.server.agent.repository.AgentToolAuditRepository
import org.springframework.stereotype.Service

@Service
class AgentTraceService(
    private val repository: AgentToolAuditRepository,
    private val objectMapper: ObjectMapper
) {
    fun listForUser(userId: String, limit: Int): List<AgentToolAuditEntity> =
        repository.findByUserIdOrderByCreatedAtDesc(userId).take(limit.coerceIn(1, 200))

    fun save(trace: AgentTraceRecord) {
        repository.save(
            AgentToolAuditEntity(
                id = trace.traceId,
                userId = trace.userId,
                sessionId = trace.sessionId,
                toolName = "LLM_CHAT_COMPLETION",
                requestSummary = sanitize(trace.query, 500),
                intent = trace.intent,
                databaseSearch = trace.databaseSearch,
                detectedKeywords = trace.detectedKeywords
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { it.lowercase() }
                    .joinToString(",")
                    .take(1000),
                detectedConcerns = trace.detectedConcerns.joinToString(",").take(500),
                matchedEntityIds = objectMapper.writeValueAsString(trace.matchedEntityIds),
                llmCalled = trace.llmCalled,
                modelName = trace.modelName.take(100),
                gatewayUrl = trace.gatewayUrl.substringBefore('?').take(255),
                resultStatus = when {
                    trace.fallbackUsed -> "FALLBACK"
                    trace.httpStatus in 200..299 -> "SUCCESS"
                    else -> "ERROR"
                },
                durationMs = trace.totalDurationMs,
                totalDurationMs = trace.totalDurationMs,
                databaseDurationMs = trace.databaseDurationMs,
                llmDurationMs = trace.llmDurationMs,
                httpStatus = trace.httpStatus,
                inputTokens = trace.inputTokens,
                outputTokens = trace.outputTokens,
                fallbackUsed = trace.fallbackUsed,
                answerLength = trace.answerLength,
                errorSummary = trace.errorSummary?.let { sanitize(it, 500) }
            )
        )
    }

    fun detectConcerns(query: String): List<String> = concernTerms.filter { query.contains(it, true) }.take(8)

    private fun sanitize(value: String, maxLength: Int): String = value
        .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._-]+"), "$1***")
        .replace(Regex("(?i)(api[_-]?key\\s*[:=]\\s*)[^\\s,;]+"), "$1***")
        .replace(Regex("\\b1[3-9]\\d{9}\\b"), "***PHONE***")
        .replace(Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"), "***EMAIL***")
        .take(maxLength)

    private companion object {
        val concernTerms = listOf("暗沉", "毛孔", "粗糙", "斑", "色沉", "痘", "皱纹", "细纹", "松弛", "下垂", "凹陷", "dull", "pores", "texture", "pigmentation", "acne", "wrinkle", "sagging", "hollow")
    }
}

data class AgentTraceRecord(
    val traceId: String,
    val userId: String,
    val sessionId: String,
    val query: String,
    val intent: String,
    val databaseSearch: Boolean,
    val detectedKeywords: List<String>,
    val detectedConcerns: List<String>,
    val matchedEntityIds: Map<String, List<String>>,
    val llmCalled: Boolean,
    val modelName: String,
    val gatewayUrl: String,
    val totalDurationMs: Long,
    val databaseDurationMs: Long,
    val llmDurationMs: Long,
    val httpStatus: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val fallbackUsed: Boolean,
    val answerLength: Int,
    val errorSummary: String?
)
