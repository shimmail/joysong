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
    fun `agent clients use the AI agent proxy`() {
        val properties = AiAgentProperties(proxyUrl = "http://127.0.0.1:8899")

        assertProxy(config.agentLlmRestTemplate(properties), Proxy.Type.HTTP, "127.0.0.1", 8899)
        assertProxy(config.agentIntentParserRestTemplate(properties), Proxy.Type.HTTP, "127.0.0.1", 8899)
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
