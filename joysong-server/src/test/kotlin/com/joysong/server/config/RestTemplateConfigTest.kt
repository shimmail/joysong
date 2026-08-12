package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.client.RestTemplate
import java.net.InetSocketAddress
import java.net.Proxy

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
    fun `shared translation client keeps the Google proxy policy`() {
        val template = config.llmRestTemplate("http://127.0.0.1:7890")

        assertProxy(template, Proxy.Type.HTTP, "127.0.0.1", 7890)
    }

    private fun assertProxy(
        template: RestTemplate,
        expectedType: Proxy.Type,
        expectedHost: String,
        expectedPort: Int
    ) {
        val factory = template.requestFactory as SimpleClientHttpRequestFactory
        val proxy = ReflectionTestUtils.getField(factory, "proxy") as Proxy
        val address = proxy.address() as InetSocketAddress
        assertEquals(expectedType, proxy.type())
        assertEquals(expectedHost, address.hostString)
        assertEquals(expectedPort, address.port)
    }
}
