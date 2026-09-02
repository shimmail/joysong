package com.joysong.server.config

import org.slf4j.LoggerFactory
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.EnvironmentAware
import org.springframework.core.env.Environment
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("demo")
class DemoProfileSafetyValidator : BeanFactoryPostProcessor, EnvironmentAware {
    private val logger = LoggerFactory.getLogger(DemoProfileSafetyValidator::class.java)
    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    @Throws(BeansException::class)
    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        validate(environment)
    }

    internal fun validate(environment: Environment) {
        val violations = mutableListOf<String>()
        val activeProfiles = environment.activeProfiles.map(String::lowercase).toSet()

        if ("prod" in activeProfiles) violations.add("the demo and prod profiles cannot be combined")
        if ("dev" in activeProfiles) violations.add("the demo and dev profiles cannot be combined")

        DEMO_DISABLED_PROPERTIES.forEach { property ->
            if (environment.getProperty(property, Boolean::class.java, false)) {
                violations.add("$property must be false in the demo profile")
            }
        }

        val databaseUrl = environment.getProperty("spring.datasource.url").orEmpty()
        if (databaseUrl.contains("createDatabaseIfNotExist", ignoreCase = true)) {
            violations.add("spring.datasource.url must not enable createDatabaseIfNotExist")
        }

        DEMO_REQUIRED_PROPERTIES.forEach { (property, expected) ->
            val actual = environment.getProperty(property)?.trim()?.lowercase()
            if (actual != expected) violations.add("$property must be $expected in the demo profile")
        }

        val expectedDatabaseName = environment
            .getProperty("seed.demo.expected-database-name")
            .orEmpty()
            .trim()
        if (!expectedDatabaseName.startsWith(SAFE_DATABASE_PREFIX)) {
            violations.add("seed.demo.expected-database-name must start with $SAFE_DATABASE_PREFIX")
        }

        if (violations.isNotEmpty()) {
            val message = "Unsafe demo profile configuration: ${violations.joinToString("; ")}"
            logger.error(message)
            throw IllegalStateException(message)
        }

        logger.info("Demo profile safety configuration validated")
    }

    private companion object {
        const val SAFE_DATABASE_PREFIX = "myapp_worktree_"

        val DEMO_DISABLED_PROPERTIES = listOf(
            "oss.enabled",
            "aliyun.sms.enabled",
            "security.verification-code.log-for-dev",
            "payment.alipay-plus.auto-pay-on-order-create-enabled",
            "payment.alipay-plus.simulated-enabled",
            "payment.stripe.legacy-enabled",
            "payment.reconciliation.enabled",
            "app.scheduling.enabled",
            "app.account-deletion.enabled",
            "app.account-deletion.allow-commerce-bypass",
        )

        val DEMO_REQUIRED_PROPERTIES = mapOf(
            "spring.jpa.hibernate.ddl-auto" to "validate",
            "spring.flyway.enabled" to "true",
            "spring.flyway.validate-on-migrate" to "true",
            "spring.flyway.baseline-on-migrate" to "false",
            "spring.flyway.clean-disabled" to "true",
        )
    }
}
