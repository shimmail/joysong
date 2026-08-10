package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Clock
import java.time.Duration

class AiAgentPropertiesTest {

    @Test
    fun `typed properties bind agent settings including the turn lease`() {
        val environment = MockEnvironment()
            .withProperty("ai-agent.enabled", "true")
            .withProperty("ai-agent.api-key", "test-key")
            .withProperty("ai-agent.base-url", "https://www.fastaitoken.com/v1")
            .withProperty("ai-agent.model", "gpt-5.5")
            .withProperty("ai-agent.proxy-url", "http://127.0.0.1:7890")
            .withProperty("ai-agent.turn-lease", "45s")

        val properties = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .get()

        assertEquals(true, properties.enabled)
        assertEquals("test-key", properties.apiKey)
        assertEquals("https://www.fastaitoken.com/v1", properties.baseUrl)
        assertEquals("gpt-5.5", properties.model)
        assertEquals("http://127.0.0.1:7890", properties.proxyUrl)
        assertEquals(Duration.ofSeconds(45), properties.turnLease)
    }

    @Test
    fun `runtime beans expose the configured lease and one stable clock`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                org.springframework.core.env.MapPropertySource(
                    "test",
                    mapOf("ai-agent.turn-lease" to "30s")
                )
            )
            context.register(AiAgentConfiguration::class.java)
            context.refresh()

            assertEquals(Duration.ofSeconds(30), context.getBean("turnLease"))
            assertSame(context.getBean(Clock::class.java), context.getBean(Clock::class.java))
        }
    }

    @Test
    fun `enabled production rejects a blank API key`() {
        val properties = validEnabledProperties().copy(apiKey = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("OPENAI_API_KEY"))
    }

    @Test
    fun `enabled production rejects a blank base URL`() {
        val properties = validEnabledProperties().copy(baseUrl = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("OPENAI_BASE_URL"))
    }

    @Test
    fun `enabled production rejects a blank model`() {
        val properties = validEnabledProperties().copy(model = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_MODEL"))
    }

    @Test
    fun `disabled production accepts blank provider credentials`() {
        val properties = AiAgentProperties(enabled = false)

        assertDoesNotThrow { validator(properties).validate() }
    }

    private fun validEnabledProperties() = AiAgentProperties(
        enabled = true,
        apiKey = "test-key",
        baseUrl = "https://www.fastaitoken.com/v1",
        model = "gpt-5.5"
    )

    private fun validator(properties: AiAgentProperties): ConfigValidator = ConfigValidator(
        environment = MockEnvironment().apply { setActiveProfiles("prod") },
        jwtSecret = "test-jwt-secret-that-is-at-least-32-characters",
        googleClientId = "test-google-client-id",
        ossAccessKeyId = "test-oss-key",
        ossAccessKeySecret = "test-oss-secret",
        ossEndpoint = "oss.example.test",
        ossBucketName = "test-bucket",
        smsAccessKeyId = "test-sms-key",
        smsAccessKeySecret = "test-sms-secret",
        smsSignName = "test-sign",
        smsTemplateCode = "test-template",
        dbPassword = "test-db-password",
        adminPhone = "13800000000",
        adminPassword = "test-admin-password",
        ossEnabled = true,
        smsEnabled = true,
        aiAgentProperties = properties,
        logVerificationCodeForDev = false,
        demoSeedEnabled = false
    )
}
