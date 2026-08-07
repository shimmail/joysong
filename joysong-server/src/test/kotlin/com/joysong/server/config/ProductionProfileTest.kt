package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

class ProductionProfileTest {

    private val properties = YamlPropertySourceLoader()
        .load("production", ClassPathResource("application-prod.yml"))
        .single()
    private val developmentProperties = YamlPropertySourceLoader()
        .load("development", ClassPathResource("application-dev.yml"))
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
    fun `production disables every demo path`() {
        assertEquals(false, properties.getProperty("seed.demo.enabled"))
        assertEquals("live", properties.getProperty("payment.mode"))
        assertEquals("\${STRIPE_ENABLED:true}", properties.getProperty("payment.stripe.enabled"))
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
    }

    @Test
    fun `development payment remains disabled until real sandbox credentials are supplied`() {
        assertEquals("disabled", developmentProperties.getProperty("payment.mode"))
        assertEquals("\${STRIPE_ENABLED:false}", developmentProperties.getProperty("payment.stripe.enabled"))
        assertEquals(true, developmentProperties.getProperty("security.verification-code.log-for-dev"))
        assertTrue(developmentProperties.getProperty("openai.demo-fallback-enabled").toString().contains("OPENAI_DEMO_FALLBACK_ENABLED"))
        assertEquals(false, developmentProperties.getProperty("oss.enabled"))
        assertEquals(false, developmentProperties.getProperty("aliyun.sms.enabled"))
    }
}
