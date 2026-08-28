package com.joysong.server.user.deletion

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import java.time.Clock

@ConfigurationProperties("app.account-deletion")
class AccountDeletionProperties {
    var enabled: Boolean = false
    var allowCommerceBypass: Boolean = false
    var hmacSecret: String = "development-only-account-deletion-secret"
    var requestTtlSeconds: Long = 1_800
    var authorizationTtlSeconds: Long = 600
}

@Configuration
@EnableConfigurationProperties(AccountDeletionProperties::class)
class AccountDeletionConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun accountDeletionClock(): Clock = Clock.systemUTC()
}

@Component
class AccountDeletionAvailability(
    private val properties: AccountDeletionProperties,
    private val environment: Environment,
) {
    fun requireEnabled() {
        if (!isEnabled()) throw AccountDeletionException(AccountDeletionErrorCode.FEATURE_DISABLED)
    }

    fun isEnabled(): Boolean = properties.enabled && !isProduction()

    fun commerceBypassAllowed(): Boolean =
        isEnabled() && properties.allowCommerceBypass && !isProduction()

    private fun isProduction(): Boolean = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
}
