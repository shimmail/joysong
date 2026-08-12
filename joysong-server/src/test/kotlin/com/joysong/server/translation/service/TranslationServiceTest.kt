package com.joysong.server.translation.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.joysong.server.config.AiAgentProperties
import com.joysong.server.translation.dto.TranslateTextRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestTemplate

class TranslationServiceTest {
    private val restTemplate = RestTemplate()
    private val server = MockRestServiceServer.createServer(restTemplate)
    private val service = TranslationService(
        restTemplate = restTemplate,
        aiAgentProperties = AiAgentProperties(
            apiKey = "test-key",
            baseUrl = "https://example.test/v1"
        )
    )

    @Test
    fun `translates structured response and reuses cache`() {
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"Recovery is going well ✨"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        val request = TranslateTextRequest("恢复得不错 ✨", "en-US", "diary")
        val first = service.translate(request)
        val second = service.translate(request)

        assertEquals("Recovery is going well ✨", first.translatedText)
        assertEquals("und", first.detectedLanguage)
        assertEquals("en-US", first.targetLanguage)
        assertFalse(first.cached)
        assertTrue(second.cached)
        server.verify()
    }

    @Test
    fun `shares cache across diary comment reply and message translations`() {
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"Hello"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        service.translate(TranslateTextRequest("你好", "en-US", "diary"))
        val fromReply = service.translate(TranslateTextRequest("你好", "en-US", "comment"))
        val fromMessage = service.translate(TranslateTextRequest("你好", "en-US", "message"))

        assertTrue(fromReply.cached)
        assertTrue(fromMessage.cached)
        server.verify()
    }

    @Test
    fun `uses qwen flash with concise translation prompt`() {
        val qwenRestTemplate = RestTemplate()
        val qwenServer = MockRestServiceServer.createServer(qwenRestTemplate)
        val qwenService = TranslationService(
            restTemplate = qwenRestTemplate,
            aiAgentProperties = AiAgentProperties(
                apiKey = "qwen-key",
                baseUrl = "https://qwen.test/v1"
            )
        )
        qwenServer.expect(requestTo("https://qwen.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("qwen3.7-flash"))
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[1].role").value("user"))
            .andExpect(jsonPath("$.enable_thinking").value(false))
            .andExpect(jsonPath("$.temperature").value(0))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"Hello"}}]}""", MediaType.APPLICATION_JSON))

        val response = qwenService.translate(TranslateTextRequest("你好", "en-US", "comment"))

        assertEquals("Hello", response.translatedText)
        assertEquals("qwen", response.provider)
        qwenServer.verify()
    }

    @Test
    fun `translation reuses agent credentials but never agent models`() {
        val agentRestTemplate = RestTemplate()
        val agentServer = MockRestServiceServer.createServer(agentRestTemplate)
        val translationService = TranslationService(
            restTemplate = agentRestTemplate,
            aiAgentProperties = AiAgentProperties(
                apiKey = "shared-agent-key",
                baseUrl = "https://agent.example.test/v1",
                model = "chat-model-must-not-be-used",
                intentModel = "intent-model-must-not-be-used"
            )
        )
        agentServer.expect(requestTo("https://agent.example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer shared-agent-key"))
            .andExpect(jsonPath("$.model").value("qwen3.7-flash"))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"Hello"}}]}""", MediaType.APPLICATION_JSON))

        val response = translationService.translate(TranslateTextRequest("你好", "en-US", "comment"))

        assertEquals("Hello", response.translatedText)
        assertEquals("qwen", response.provider)
        agentServer.verify()
    }

    @Test
    fun `rejects invalid language tag`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.translate(TranslateTextRequest("hello", "not_a_language", "general"))
        }
    }

    @Test
    fun `translation failure logs only controlled diagnostics without the provider response body`() {
        val sensitiveBody = "private diary test-key private@example.com 13800000000"
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withStatus(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"error":{"code":"private_model_customer_42","message":"$sensitiveBody"}}""")
            )

        val logs = captureTranslationLogs {
            assertThrows(IllegalStateException::class.java) {
                service.translate(TranslateTextRequest("sensitive request", "en-US", "diary"))
            }
        }
        val joined = logs.joinToString("\n")

        assertTrue(joined.contains("category=HTTP_ERROR"))
        assertTrue(joined.contains("httpStatus=502"))
        assertTrue(joined.contains("errorCode=UPSTREAM_5XX"))
        listOf(
            sensitiveBody,
            "private diary",
            "test-key",
            "private@example.com",
            "13800000000",
            "private_model_customer_42"
        )
            .forEach { sensitive -> assertFalse(joined.contains(sensitive, ignoreCase = true)) }
        server.verify()
    }

    private fun captureTranslationLogs(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(TranslationService::class.java) as Logger
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
