package com.joysong.server.agent.diagnostics

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

@Component
class AgentOperationLogger {
    private val logger = LoggerFactory.getLogger(AgentOperationLogger::class.java)

    fun completed(
        traceId: String,
        turnId: String,
        sessionId: String,
        durationMs: Long,
        modelName: String
    ) {
        logger.info(
            "AGENT_OPERATION traceId={} turnId={} sessionHash={} operation=MODEL_COMPLETION terminalStatus=SUCCEEDED durationMs={} modelName={}",
            safeId(traceId),
            safeId(turnId),
            sessionHash(sessionId),
            durationMs.coerceAtLeast(0),
            safeModelName(modelName)
        )
    }

    fun failed(
        traceId: String,
        turnId: String,
        sessionId: String,
        durationMs: Long,
        errorCode: String
    ) {
        logger.info(
            "AGENT_OPERATION traceId={} turnId={} sessionHash={} operation=MODEL_COMPLETION terminalStatus=FAILED durationMs={} errorCode={}",
            safeId(traceId),
            safeId(turnId),
            sessionHash(sessionId),
            durationMs.coerceAtLeast(0),
            safeErrorCode(errorCode)
        )
    }

    fun providerFailed(
        traceId: String,
        turnId: String,
        providerPhase: String,
        providerHost: String,
        modelName: String,
        httpStatus: Int?,
        providerCategory: String,
        providerErrorCode: String?,
        exceptionType: String,
        durationMs: Long,
        messageCount: Int,
        systemMessageCount: Int,
        totalCharacterCount: Int
    ) {
        logger.warn(
            "AGENT_PROVIDER operation=PROVIDER_CALL traceId={} turnId={} providerPhase={} providerHost={} modelName={} httpStatus={} providerCategory={} providerErrorCode={} exceptionType={} durationMs={} messageCount={} systemMessageCount={} totalCharacterCount={}",
            safeId(traceId),
            safeId(turnId),
            safeProviderPhase(providerPhase),
            safeProviderHost(providerHost),
            safeModelName(modelName),
            httpStatus?.takeIf { it in 100..599 }?.toString() ?: "none",
            safeProviderCategory(providerCategory),
            safeProviderErrorCode(providerErrorCode),
            safeExceptionType(exceptionType),
            durationMs.coerceAtLeast(0),
            messageCount.coerceIn(0, 100),
            systemMessageCount.coerceIn(0, 100),
            totalCharacterCount.coerceIn(0, 1_000_000)
        )
    }

    private fun sessionHash(sessionId: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(StandardCharsets.UTF_8))
    ).take(16)

    private fun safeId(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' }.take(64)

    private fun safeModelName(@Suppress("UNUSED_PARAMETER") value: String): String = "redacted"

    private fun safeErrorCode(value: String): String = value.trim().uppercase()
        .takeIf(stableErrorCodes::contains)
        ?: "AGENT_INTERNAL_ERROR"

    private fun safeProviderPhase(value: String): String = value.trim().uppercase()
        .takeIf(providerPhases::contains)
        ?: "UNKNOWN"

    private fun safeProviderHost(value: String): String = value.trim().lowercase()
        .takeIf(safeProviderHostPattern::matches)
        ?: "redacted"

    private fun safeProviderCategory(value: String): String = value.trim().uppercase()
        .takeIf(providerCategories::contains)
        ?: "UNKNOWN"

    private fun safeProviderErrorCode(value: String?): String = value.orEmpty().trim().lowercase()
        .takeIf { safeProviderErrorCodePattern.matches(it) && !sensitiveMetadataPattern.containsMatchIn(it) }
        ?: "none"

    private fun safeExceptionType(value: String): String = value.trim()
        .takeIf(safeExceptionTypePattern::matches)
        ?: "redacted"

    private companion object {
        val safeProviderHostPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
        val safeProviderErrorCodePattern = Regex("[a-z0-9][a-z0-9._-]{0,79}")
        val safeExceptionTypePattern = Regex("[A-Za-z][A-Za-z0-9_$.-]{0,119}")
        val sensitiveMetadataPattern = Regex("(?i)(authorization|bearer|token|api[_-]?key|test[_-]?key)")
        val providerPhases = setOf("INTENT_CLASSIFICATION", "MODEL_COMPLETION")
        val providerCategories = setOf(
            "AUTH",
            "RATE_LIMIT",
            "MODEL_NOT_FOUND",
            "INVALID_REQUEST",
            "UPSTREAM_5XX",
            "CONNECT_TIMEOUT",
            "READ_TIMEOUT",
            "NETWORK",
            "INVALID_RESPONSE",
            "UNKNOWN"
        )
        val stableErrorCodes = setOf(
            "INVALID_REQUEST",
            "INVALID_IDEMPOTENCY_KEY",
            "SESSION_NOT_FOUND",
            "AGENT_STREAMING_DISABLED",
            "TURN_IN_PROGRESS",
            "IDEMPOTENCY_KEY_CONFLICT",
            "IDEMPOTENCY_EXPIRED",
            "AI_PROVIDER_TIMEOUT",
            "AI_PROVIDER_UNAVAILABLE",
            "AGENT_INTERNAL_ERROR"
        )
    }
}
