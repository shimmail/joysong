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

    @Bean("intentParserRestTemplate")
    fun intentParserRestTemplate(
        @Value("\${google.proxy-url:}") proxyUrl: String
    ): org.springframework.web.client.RestTemplate = createRestTemplate(proxyUrl, 3_000, 8_000)

    private fun createRestTemplate(
        proxyUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): org.springframework.web.client.RestTemplate {
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(connectTimeoutMs)
        factory.setReadTimeout(readTimeoutMs)

        if (proxyUrl.isNotBlank()) {
            val uri = java.net.URI(proxyUrl)
            val proxy = Proxy(
                if (uri.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress(uri.host, uri.port)
            )
            factory.setProxy(proxy)
        }

        return org.springframework.web.client.RestTemplate(factory)
    }
}
