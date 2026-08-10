package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.core.env.StandardEnvironment

class ProductionProfileTest {

    private val properties = YamlPropertySourceLoader()
        .load("production", ClassPathResource("application-prod.yml"))
        .single()
    private val developmentProperties = YamlPropertySourceLoader()
        .load("development", ClassPathResource("application-dev.example.yml"))
        .single()
    private val applicationProperties = YamlPropertySourceLoader()
        .load("application", ClassPathResource("application.yml"))
        .single()

    @Test
    fun `production database and migration settings fail closed`() {
        assertEquals("prod", properties.getProperty("spring.config.activate.on-profile"))
        assertEquals("\${DB_URL}", properties.getProperty("spring.datasource.url"))
        assertFalse(properties.getProperty("spring.datasource.url").toString().contains("createDatabaseIfNotExist"))
        assertEquals(false, properties.getProperty("spring.flyway.baseline-on-migrate"))
        assertEquals(true, properties.getProperty("spring.flyway.validate-on-migrate"))
        assertEquals(true, properties.getProperty("spring.flyway.clean-disabled"))
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"))
    }

    @Test
    fun `production payment environment is selected by the Stripe API key`() {
        assertEquals(false, properties.getProperty("seed.demo.enabled"))
        assertNull(properties.getProperty("payment.mode"))
        assertNull(properties.getProperty("payment.stripe.enabled"))
        assertEquals(false, properties.getProperty("security.verification-code.log-for-dev"))
        assertEquals(false, properties.getProperty("openai.demo-fallback-enabled"))
    }

    @Test
    fun `production external services require environment backed configuration`() {
        assertEquals(true, properties.getProperty("oss.enabled"))
        assertEquals(true, properties.getProperty("aliyun.sms.enabled"))
        assertEquals("\${OSS_BUCKET_NAME}", properties.getProperty("oss.bucket-name"))
        assertEquals("\${SMS_SIGN_NAME}", properties.getProperty("aliyun.sms.sign-name"))
        assertTrue(properties.getProperty("aliyun.sms.template-code").toString().contains("SMS_TEMPLATE_CODE"))
        assertEquals("\${OPENAI_BASE_URL:}", properties.getProperty("openai.base-url"))
    }

    @Test
    fun `development does not override the Stripe API key selected environment`() {
        assertNull(developmentProperties.getProperty("payment.mode"))
        assertNull(developmentProperties.getProperty("payment.stripe.enabled"))
        assertEquals(true, developmentProperties.getProperty("security.verification-code.log-for-dev"))
        assertEquals(false, developmentProperties.getProperty("oss.enabled"))
        assertEquals(false, developmentProperties.getProperty("aliyun.sms.enabled"))
    }

    @Test
    fun `development profile enables demo fallback through typed agent properties`() {
        val environment = StandardEnvironment().apply {
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            propertySources.addLast(developmentProperties)
            propertySources.addLast(applicationProperties)
        }

        val aiAgent = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .get()

        assertTrue(aiAgent.demoFallbackEnabled)
    }
}
