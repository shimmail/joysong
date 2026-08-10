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

    private fun sessionHash(sessionId: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(StandardCharsets.UTF_8))
    ).take(16)

    private fun safeId(value: String): String = value.filter { it.isLetterOrDigit() || it == '-' }.take(64)

    private fun safeModelName(value: String): String = value.trim().takeIf { candidate ->
        safeModelNamePattern.matches(candidate) &&
            !sensitiveMetadataPattern.containsMatchIn(candidate) &&
            !phonePattern.containsMatchIn(candidate)
    } ?: "redacted"

    private fun safeErrorCode(value: String): String = value.trim().uppercase()
        .takeIf(stableErrorCodes::contains)
        ?: "AGENT_INTERNAL_ERROR"

    private companion object {
        val safeModelNamePattern = Regex("[A-Za-z][A-Za-z0-9._-]{0,79}")
        val sensitiveMetadataPattern = Regex("(?i)(authorization|bearer|token|api[_-]?key|test[_-]?key)")
        val phonePattern = Regex("1[3-9]\\d{9}")
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
