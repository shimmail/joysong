package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAgentProperties::class)
class AiAgentConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("turnLease")
    fun turnLease(properties: AiAgentProperties): Duration = properties.turnLease
}
