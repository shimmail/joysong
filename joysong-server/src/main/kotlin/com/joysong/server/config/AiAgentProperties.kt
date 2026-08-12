package com.joysong.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("ai-agent")
data class AiAgentProperties(
    var provider: AiAgentProvider? = null,
    var apiKey: String = "",
    var baseUrl: String = "",
    var model: String = "",
    var intentModel: String = ""
) {
    val enabled: Boolean get() = true
    val proxyUrl: String get() = ""
    val turnLease: Duration get() = AiAgentRuntimePolicy.TURN_LEASE
    val intentParserEnabled: Boolean get() = true
    val demoFallbackEnabled: Boolean get() = false

    fun resolvedIntentModel(): String = intentModel.trim().ifBlank { model.trim() }
}

object AiAgentRuntimePolicy {
    val TURN_LEASE: Duration = Duration.ofSeconds(90)
}

object AiAgentHttpBudget {
    const val COMPLETION_CONNECT_TIMEOUT_MS = 10_000
    const val COMPLETION_READ_TIMEOUT_MS = 60_000
    const val INTENT_CONNECT_TIMEOUT_MS = 3_000
    const val INTENT_READ_TIMEOUT_MS = 8_000
    val maximumSerialRequestDuration: Duration = Duration.ofSeconds(81)
    val minimumTurnLease: Duration = Duration.ofSeconds(82)

    fun isTurnLeaseSafe(value: Duration): Boolean = value >= minimumTurnLease
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiAgentProperties::class)
class AiAgentConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean("turnLease")
    fun turnLease(): Duration = AiAgentRuntimePolicy.TURN_LEASE
}
