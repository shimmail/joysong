package com.joysong.server.config

import com.joysong.server.translation.service.TranslationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.ClientHttpResponse
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import java.io.InputStream

class RestTemplateConfigTest {

    private val config = RestTemplateConfig()

    @Test
    fun `explicit intent model overrides the answer model`() {
        val properties = AiAgentProperties(model = "answer-model", intentModel = " intent-small ")

        assertEquals("intent-small", properties.resolvedIntentModel())
    }

    @Test
    fun `blank intent model falls back to the answer model`() {
        val properties = AiAgentProperties(model = "answer-model", intentModel = "   ")

        assertEquals("answer-model", properties.resolvedIntentModel())
    }

    @Test
    fun `agent clients use fixed direct connection policy`() {
        assertEquals(null, ReflectionTestUtils.getField(config.agentLlmRestTemplate().requestFactory, "proxy"))
        assertEquals(null, ReflectionTestUtils.getField(config.agentIntentParserRestTemplate().requestFactory, "proxy"))
    }

    @Test
    fun `AI clients preserve status and headers without reading error response bodies`() {
        val templates = listOf(
            config.agentLlmRestTemplate(),
            config.agentIntentParserRestTemplate(),
            config.translationRestTemplate()
        )

        templates.forEach { template ->
            assertStatusOnlyError(template, HttpStatus.TOO_MANY_REQUESTS)
            assertStatusOnlyError(template, HttpStatus.SERVICE_UNAVAILABLE)
        }
    }

    @Test
    fun `configuration does not publish unused legacy LLM client`() {
        AnnotationConfigApplicationContext(RestTemplateConfig::class.java).use { context ->
            assertEquals(false, context.containsBean("llmRestTemplate"))
        }
    }

    @Test
    fun `translation traffic ignores the Google identity proxy`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    "test",
                    mapOf(
                        "google.proxy-url" to "http://127.0.0.1:7890",
                        "ai-agent.api-key" to "test-key",
                        "ai-agent.base-url" to "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    )
                )
            )
            context.register(AiAgentConfiguration::class.java, RestTemplateConfig::class.java, TranslationService::class.java)
            context.refresh()

            val service = context.getBean(TranslationService::class.java)
            val template = ReflectionTestUtils.getField(service, "restTemplate") as RestTemplate
            assertEquals(null, ReflectionTestUtils.getField(template.requestFactory, "proxy"))
        }
    }

    private fun assertStatusOnlyError(template: RestTemplate, status: HttpStatus) {
        val response = unreadableBodyResponse(status)

        val exception = assertThrows(HttpStatusCodeException::class.java) {
            template.errorHandler.handleError(response)
        }

        assertEquals(status, exception.statusCode)
        assertEquals("request-123", exception.responseHeaders?.getFirst("X-Request-Id"))
        assertEquals(0, exception.responseBodyAsByteArray.size)
    }

    private fun unreadableBodyResponse(status: HttpStatus): ClientHttpResponse =
        object : ClientHttpResponse {
            override fun getStatusCode(): HttpStatusCode = status

            override fun getStatusText(): String = status.reasonPhrase

            override fun getHeaders(): HttpHeaders = HttpHeaders().apply {
                set("X-Request-Id", "request-123")
            }

            override fun getBody(): InputStream = object : InputStream() {
                override fun read(): Int = throw AssertionError("error response body must not be read")
            }

            override fun close() = Unit
        }
}
