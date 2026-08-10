package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("ai-agent")
data class AiAgentProperties(
    var enabled: Boolean = false,
    var apiKey: String = "",
    var baseUrl: String = "",
    var model: String = "",
    var proxyUrl: String = "",
    var turnLease: Duration = Duration.ofSeconds(90),
    var intentParserEnabled: Boolean = true,
    var demoFallbackEnabled: Boolean = false
)

object AiAgentProxyUrlPolicy {
    private val allowedSchemes = setOf("http", "https", "socks")

    fun isAllowed(value: String): Boolean {
        if (value.isBlank()) return true
        return try {
            val uri = URI(value.trim())
            uri.scheme?.lowercase() in allowedSchemes &&
                !uri.host.isNullOrBlank() &&
                uri.port in 1..65535 &&
                uri.userInfo == null
        } catch (_: URISyntaxException) {
            false
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAgentProperties::class)
class AiAgentConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("turnLease")
    fun turnLease(properties: AiAgentProperties): Duration = properties.turnLease
}
