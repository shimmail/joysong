package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `AI agent is enabled by default`() {
        assertTrue(AiAgentProperties().enabled)
    }

    @Test
    fun `typed properties bind only the five deployment settings`() {
        val environment = MockEnvironment()
            .withProperty("ai-agent.provider", "qWeN")
            .withProperty("ai-agent.api-key", "test-key")
            .withProperty("ai-agent.base-url", "https://www.fastaitoken.com/v1")
            .withProperty("ai-agent.model", "gpt-5.5")
            .withProperty("ai-agent.intent-model", "intent-model")

        val properties = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .get()

        assertEquals(AiAgentProvider.QWEN, properties.provider)
        assertEquals("test-key", properties.apiKey)
        assertEquals("https://www.fastaitoken.com/v1", properties.baseUrl)
        assertEquals("gpt-5.5", properties.model)
        assertEquals("intent-model", properties.intentModel)
    }

    @Test
    fun `unknown provider values are rejected by property binding`() {
        val environment = MockEnvironment().withProperty("ai-agent.provider", "anthropic")

        assertThrows(Exception::class.java) {
            Binder.get(environment).bind("ai-agent", Bindable.of(AiAgentProperties::class.java)).get()
        }
    }

    @Test
    fun `intent model is optional and defaults to the chat model`() {
        val properties = AiAgentProperties(model = "qwen3.7-flash", intentModel = " ")

        assertEquals("qwen3.7-flash", properties.resolvedIntentModel())
    }

    @Test
    fun `agent properties do not bind legacy or translation credentials`() {
        val environment = MockEnvironment()
            .withProperty("openai.api-key", "legacy-key")
            .withProperty("translation.qwen.api-key", "translation-key")

        val properties = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .orElseGet(::AiAgentProperties)

        assertEquals("", properties.apiKey)
    }

    @Test
    fun `obsolete agent settings are rejected instead of overriding fixed runtime policy`() {
        val environment = MockEnvironment()
            .withProperty("ai-agent.enabled", "false")
            .withProperty("ai-agent.proxy-url", "http://127.0.0.1:7890")
            .withProperty("ai-agent.turn-lease", "120s")
            .withProperty("ai-agent.intent-parser-enabled", "false")
            .withProperty("ai-agent.demo-fallback-enabled", "true")

        assertThrows(Exception::class.java) {
            Binder.get(environment).bind("ai-agent", Bindable.of(AiAgentProperties::class.java)).get()
        }
    }

    @Test
    fun `provider URL policy accepts and normalizes Qwen compatible-mode endpoints`() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            AiAgentProviderUrlPolicy.normalizeAllowed(
                AiAgentProvider.QWEN,
                "  HTTPS://DASHSCOPE.ALIYUNCS.COM:443/compatible-mode/v1/  "
            )
        )
    }

    @Test
    fun `provider URL policy accepts and normalizes approved OpenAI-compatible endpoints`() {
        assertEquals(
            "https://www.fastaitoken.com/v1",
            AiAgentProviderUrlPolicy.normalizeAllowed(
                AiAgentProvider.OPENAI_COMPATIBLE,
                "  HTTPS://WWW.FASTAITOKEN.COM:443/v1/  "
            )
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/extra"
        ]
    )
    fun `provider URL policy rejects Qwen paths that are not the approved base path`(url: String) {
        assertNull(AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.QWEN, url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.fastaitoken.com",
            "https://www.fastaitoken.com/chat/completions",
            "https://www.fastaitoken.com/v1/chat/completions",
            "https://www.fastaitoken.com/v1/extra"
        ]
    )
    fun `provider URL policy rejects compatible paths that are not the approved base path`(url: String) {
        assertNull(AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.OPENAI_COMPATIBLE, url))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://www.fastaitoken.com/v1",
            "https://dashscope.aliyuncs.com/v1",
            "http://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://user:secret@dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/v1?key=secret",
            "https://dashscope.aliyuncs.com/compatible-mode/v1#fragment",
            "https://dashscope.aliyuncs.com:444/compatible-mode/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/../v1",
            "https://dashscope.aliyuncs.com/compatible-mode/%2e%2e/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/%2E/v1",
            "https://dashscope.aliyuncs.com/compatible-mode/%2e%2e%2foutside",
            "https://dashscope.aliyuncs.com/compatible-mode/%2E%2E%2Foutside",
            "https://dashscope.aliyuncs.com/compatible-mode/%2e%2e%5coutside",
            "https://dashscope.aliyuncs.com/compatible-mode/%2E%2E%5Coutside"
        ]
    )
    fun `provider URL policy rejects mismatched or unsafe Qwen endpoints`(url: String) {
        assertNull(AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.QWEN, url))
    }

    @Test
    fun `runtime beans expose the configured lease and one stable system default clock`() {
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(
                org.springframework.core.env.MapPropertySource("test", emptyMap())
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
    fun `enabled production rejects a blank API key`() {
        val properties = validEnabledProperties().copy(apiKey = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_API_KEY"))
    }

    @Test
    fun `enabled production rejects a blank base URL`() {
        val properties = validEnabledProperties().copy(baseUrl = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_BASE_URL"))
    }

    @Test
    fun `enabled production rejects a blank model`() {
        val properties = validEnabledProperties().copy(model = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_MODEL"))
    }

    @Test
    fun `enabled production rejects a blank provider`() {
        val properties = validEnabledProperties().copy(provider = null)

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_PROVIDER"))
    }

    @Test
    fun `enabled production rejects a blank intent model`() {
        val properties = validEnabledProperties().copy(intentModel = "")

        val error = assertThrows(IllegalStateException::class.java) { validator(properties).validate() }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_INTENT_MODEL"))
    }

    @Test
    fun `default profile rejects an invalid provider base URL combination`() {
        val properties = validEnabledProperties().copy(
            provider = AiAgentProvider.QWEN,
            baseUrl = "https://www.fastaitoken.com/v1"
        )

        val error = assertThrows(IllegalStateException::class.java) {
            validator(properties, profile = "default").validate()
        }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_BASE_URL"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "dev", "prod"])
    fun `complete deployment rejects OpenAI compatible provider in every profile`(profile: String) {
        val properties = validEnabledProperties().copy(
            provider = AiAgentProvider.OPENAI_COMPATIBLE,
            baseUrl = "https://www.fastaitoken.com/v1"
        )

        val error = assertThrows(IllegalStateException::class.java) {
            validator(properties, profile = profile).validate()
        }

        assertTrue(error.message.orEmpty().contains("AI_AGENT_PROVIDER=QWEN"))
    }

    @Test
    fun `development profile normalizes an approved provider base URL`() {
        val properties = validEnabledProperties().copy(
            baseUrl = "  HTTPS://DASHSCOPE.ALIYUNCS.COM:443/compatible-mode/v1/  "
        )

        validator(properties, profile = "dev").validate()

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", properties.baseUrl)
    }

    @Test
    fun `enabled production stores the normalized approved base URL for runtime use`() {
        val properties = validEnabledProperties().copy(
            baseUrl = "  HTTPS://DASHSCOPE.ALIYUNCS.COM:443/compatible-mode/v1/  "
        )

        validator(properties).validate()

        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", properties.baseUrl)
    }

    private fun validEnabledProperties() = AiAgentProperties(
        provider = AiAgentProvider.QWEN,
        apiKey = "test-key",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        intentModel = "intent-model"
    )

    private fun validator(
        properties: AiAgentProperties,
        profile: String = "prod"
    ): ConfigValidator = ConfigValidator(
        environment = MockEnvironment().apply { setActiveProfiles(profile) },
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
