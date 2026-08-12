package com.joysong.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory

@Configuration
class RestTemplateConfig {
    @Bean("translationRestTemplate")
    fun translationRestTemplate(): org.springframework.web.client.RestTemplate =
        createRestTemplate(10_000, 60_000)

    @Bean("agentLlmRestTemplate")
    fun agentLlmRestTemplate(): org.springframework.web.client.RestTemplate =
        createRestTemplate(
            AiAgentHttpBudget.COMPLETION_CONNECT_TIMEOUT_MS,
            AiAgentHttpBudget.COMPLETION_READ_TIMEOUT_MS
        )

    @Bean("agentIntentParserRestTemplate")
    fun agentIntentParserRestTemplate(): org.springframework.web.client.RestTemplate =
        createRestTemplate(
            AiAgentHttpBudget.INTENT_CONNECT_TIMEOUT_MS,
            AiAgentHttpBudget.INTENT_READ_TIMEOUT_MS
        )

    private fun createRestTemplate(
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): org.springframework.web.client.RestTemplate {
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(connectTimeoutMs)
        factory.setReadTimeout(readTimeoutMs)
        return org.springframework.web.client.RestTemplate(factory)
    }
}
