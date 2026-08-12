package com.joysong.server.config

import com.joysong.server.translation.service.TranslationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestTemplate

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
}
