package com.joysong.server.agent.diagnostics

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class AgentOperationLoggerTest {
    private val operationLogger = AgentOperationLogger()

    @Test
    fun `completion and provider logs never expose an ordinary model name`() {
        val rawModelName = "customer-private-model-2026"

        val logs = captureLogs {
            operationLogger.completed("trace-1", "turn-1", "session-1", 12, rawModelName)
            operationLogger.providerFailed(
                traceId = "trace-2",
                turnId = "turn-2",
                providerPhase = "MODEL_COMPLETION",
                providerHost = "www.fastaitoken.com",
                modelName = rawModelName,
                httpStatus = 429,
                providerCategory = "RATE_LIMIT",
                providerErrorCode = "rate_limit_exceeded",
                exceptionType = "HttpClientErrorException",
                durationMs = 34,
                messageCount = 2,
                systemMessageCount = 1,
                totalCharacterCount = 128
            )
        }
        val joined = logs.joinToString("\n")

        assertFalse(joined.contains(rawModelName))
        assertTrue(logs.all { it.contains("modelName=redacted") })
    }

    private fun captureLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(AgentOperationLogger::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
