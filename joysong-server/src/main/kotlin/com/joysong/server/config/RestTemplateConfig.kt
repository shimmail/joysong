package com.joysong.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import java.net.InetSocketAddress
import java.net.Proxy

@Configuration
class RestTemplateConfig {
    @Bean("llmRestTemplate")
    fun llmRestTemplate(
        @Value("\${google.proxy-url:}") proxyUrl: String
    ): org.springframework.web.client.RestTemplate {
        return createRestTemplate(proxyUrl, 10_000, 60_000)
    }

    @Bean("agentLlmRestTemplate")
    fun agentLlmRestTemplate(properties: AiAgentProperties): org.springframework.web.client.RestTemplate =
        createRestTemplate(
            properties.proxyUrl,
            AiAgentHttpBudget.COMPLETION_CONNECT_TIMEOUT_MS,
            AiAgentHttpBudget.COMPLETION_READ_TIMEOUT_MS
        )

    @Bean("agentIntentParserRestTemplate")
    fun agentIntentParserRestTemplate(properties: AiAgentProperties): org.springframework.web.client.RestTemplate =
        createRestTemplate(
            properties.proxyUrl,
            AiAgentHttpBudget.INTENT_CONNECT_TIMEOUT_MS,
            AiAgentHttpBudget.INTENT_READ_TIMEOUT_MS
        )

    private fun createRestTemplate(
        proxyUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): org.springframework.web.client.RestTemplate {
        if (!AiAgentProxyUrlPolicy.isAllowed(proxyUrl)) {
            throw IllegalStateException("Invalid OPENAI_PROXY_URL configuration")
        }
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(connectTimeoutMs)
        factory.setReadTimeout(readTimeoutMs)

        if (proxyUrl.isNotBlank()) {
            val uri = java.net.URI(proxyUrl.trim())
            val proxy = Proxy(
                if (uri.scheme.equals("socks", ignoreCase = true)) Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress(uri.host, uri.port)
            )
            factory.setProxy(proxy)
        }

        return org.springframework.web.client.RestTemplate(factory)
    }
}
