package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

class AiAgentPropertiesTest {

    @Test
    fun `typed properties bind agent settings including the turn lease`() {
        val environment = MockEnvironment()
            .withProperty("ai-agent.enabled", "true")
            .withProperty("ai-agent.api-key", "test-key")
            .withProperty("ai-agent.base-url", "https://www.fastaitoken.com/v1")
            .withProperty("ai-agent.model", "gpt-5.5")
            .withProperty("ai-agent.proxy-url", "http://127.0.0.1:7890")
            .withProperty("ai-agent.turn-lease", "90s")

        val properties = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .get()

        assertEquals(true, properties.enabled)
        assertEquals("test-key", properties.apiKey)
        assertEquals("https://www.fastaitoken.com/v1", properties.baseUrl)
        assertEquals("gpt-5.5", properties.model)
        assertEquals("http://127.0.0.1:7890", properties.proxyUrl)
        assertEquals(Duration.ofSeconds(90), properties.turnLease)
    }

    @Test
    fun `runtime beans expose the configured lease and one stable system default clock`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                org.springframework.core.env.MapPropertySource(
                    "test",
                    mapOf("ai-agent.turn-lease" to "90s")
                )
            )
            context.register(AiAgentConfiguration::class.java)
            context.refresh()

            assertEquals(Duration.ofSeconds(90), context.getBean("turnLease"))
            val clock = context.getBean(Clock::class.java)
            assertEquals(ZoneId.systemDefault(), clock.zone)
            assertSame(clock, context.getBean(Clock::class.java))
        }
    }

    @Test
    fun `runtime configuration rejects a lease that cannot cover serial provider calls`() {
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(
            org.springframework.core.env.MapPropertySource(
                "test",
                mapOf("ai-agent.turn-lease" to "81s")
            )
        )
        context.register(AiAgentConfiguration::class.java)

        val error = assertThrows(Exception::class.java) { context.refresh() }

        assertTrue(error.causeChain().contains("AI_AGENT_TURN_LEASE_SECONDS"))
        context.close()
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
    fun `enabled production stores the normalized approved base URL for runtime use`() {
        val properties = validEnabledProperties().copy(
            baseUrl = "  HTTPS://WWW.FASTAITOKEN.COM:443/v1/  "
        )

        validator(properties).validate()

        assertEquals("https://www.fastaitoken.com/v1", properties.baseUrl)
    }

    @Test
    fun `disabled production accepts blank provider credentials`() {
        val properties = AiAgentProperties(enabled = false)

        assertDoesNotThrow { validator(properties).validate() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-1", "0", "81"])
    fun `production rejects non-positive or under-budget turn leases`(leaseSeconds: String) {
        val properties = validEnabledProperties().copy(turnLease = Duration.ofSeconds(leaseSeconds.toLong()))

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_TURN_LEASE_SECONDS"))
    }

    @Test
    fun `production accepts the explicit minimum safe turn lease`() {
        val properties = validEnabledProperties().copy(turnLease = Duration.ofSeconds(82))

        assertDoesNotThrow { validator(properties).validate() }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ftp://proxy.example.test:8080",
            "http:///missing-host:8080",
            "http://proxy.example.test",
            "http://proxy.example.test:0",
            "http://proxy.example.test:65536",
            "https://proxy.example.test:8443",
            "http://user:secret@proxy.example.test:8080",
            "not a uri"
        ]
    )
    fun `invalid proxy settings fail validation without exposing their value`(proxyUrl: String) {
        val properties = AiAgentProperties(enabled = false, proxyUrl = proxyUrl)

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("OPENAI_PROXY_URL"))
        assertFalse(error.message.orEmpty().contains(proxyUrl))
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

    private fun Throwable?.causeChain(): String = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
}
