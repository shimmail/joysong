package com.joysong.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResponseErrorHandler

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
        return org.springframework.web.client.RestTemplate(factory).apply {
            errorHandler = StatusOnlyResponseErrorHandler
        }
    }

    private object StatusOnlyResponseErrorHandler : ResponseErrorHandler {
        override fun hasError(response: org.springframework.http.client.ClientHttpResponse): Boolean =
            response.statusCode.isError

        override fun handleError(response: org.springframework.http.client.ClientHttpResponse) {
            val status = response.statusCode
            val statusText = response.statusText
            val headers = response.headers
            val emptyBody = ByteArray(0)

            when {
                status.is4xxClientError -> throw HttpClientErrorException.create(
                    status,
                    statusText,
                    headers,
                    emptyBody,
                    null
                )

                status.is5xxServerError -> throw HttpServerErrorException.create(
                    status,
                    statusText,
                    headers,
                    emptyBody,
                    null
                )
            }
        }
    }
}
