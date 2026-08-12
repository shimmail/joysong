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
import java.nio.file.Files
import java.nio.file.Path

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
        assertNull(properties.getProperty("openai.demo-fallback-enabled"))
    }

    @Test
    fun `production external services require environment backed configuration`() {
        assertEquals(true, properties.getProperty("oss.enabled"))
        assertEquals(true, properties.getProperty("aliyun.sms.enabled"))
        assertEquals("\${OSS_BUCKET_NAME}", properties.getProperty("oss.bucket-name"))
        assertEquals("\${SMS_SIGN_NAME}", properties.getProperty("aliyun.sms.sign-name"))
        assertTrue(properties.getProperty("aliyun.sms.template-code").toString().contains("SMS_TEMPLATE_CODE"))
        assertNull(properties.getProperty("openai.base-url"))
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
    fun `development profile cannot enable fixed agent fallback policy`() {
        val environment = StandardEnvironment().apply {
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            propertySources.addLast(developmentProperties)
            propertySources.addLast(applicationProperties)
        }

        val aiAgent = Binder.get(environment)
            .bind("ai-agent", Bindable.of(AiAgentProperties::class.java))
            .get()

        assertFalse(aiAgent.demoFallbackEnabled)
    }

    @Test
    fun `agent deployment contract exposes exactly five environment variables`() {
        val yamlText = listOf("application.yml", "application-prod.yml", "application-dev.example.yml")
            .joinToString("\n") { ClassPathResource(it).inputStream.bufferedReader().use { reader -> reader.readText() } }
        val envText = Files.readString(Path.of(".env.example"))
        val documentationRoots = listOf(Path.of("..", "doc"), Path.of("..", "docs"))
        val deploymentDocumentation = documentationRoots.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".md") &&
                        !path.normalize().toString().replace('\\', '/').contains("/docs/superpowers/")
                }.map(Files::readString).toList()
            }
        }.joinToString("\n")
        val environmentName = Regex("\\b(?:AI_AGENT|OPENAI|QWEN|TRANSLATION)_[A-Z0-9_]+\\b")
        val expected = setOf(
            "AI_AGENT_PROVIDER",
            "AI_AGENT_API_KEY",
            "AI_AGENT_BASE_URL",
            "AI_AGENT_MODEL",
            "AI_AGENT_INTENT_MODEL"
        )

        assertEquals(expected, environmentName.findAll(yamlText).map { it.value }.toSet())
        assertEquals(expected, environmentName.findAll(envText).map { it.value }.toSet())
        val documentationWithoutMarkdownFileNames = deploymentDocumentation.replace(Regex("[A-Z0-9_]+\\.md"), "")
        assertEquals(expected, environmentName.findAll(documentationWithoutMarkdownFileNames).map { it.value }.toSet())
        assertEquals("\${AI_AGENT_PROVIDER:}", applicationProperties.getProperty("ai-agent.provider"))
        assertEquals("\${AI_AGENT_API_KEY:}", applicationProperties.getProperty("ai-agent.api-key"))
        assertEquals("\${AI_AGENT_BASE_URL:}", applicationProperties.getProperty("ai-agent.base-url"))
        assertEquals("\${AI_AGENT_MODEL:}", applicationProperties.getProperty("ai-agent.model"))
        assertEquals("\${AI_AGENT_INTENT_MODEL:}", applicationProperties.getProperty("ai-agent.intent-model"))
    }

    @Test
    fun `Qwen environment example uses compatible chat and intent models`() {
        val envLines = Files.readAllLines(Path.of(".env.example"))
            .filter { it.startsWith("AI_AGENT_") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals("qwen", envLines["AI_AGENT_PROVIDER"])
        assertEquals("qwen-plus", envLines["AI_AGENT_MODEL"])
        assertEquals("qwen-turbo", envLines["AI_AGENT_INTENT_MODEL"])
    }

    @Test
    fun `current Qwen documentation has no obsolete translation fallback or relay guidance`() {
        val translationGuide = Files.readString(Path.of("..", "docs", "AI_TRANSLATION_SOLUTION.md"))
        val rolloutGuide = Files.readString(Path.of("..", "docs", "AI_AGENT_ROLLOUT.md"))

        assertFalse(translationGuide.contains("OpenAI", ignoreCase = true))
        assertFalse(rolloutGuide.contains("FastAIToken", ignoreCase = true))
    }

    @Test
    fun `current documentation contains no obsolete Agent configuration or network examples`() {
        val currentDocumentation = listOf(Path.of("..", "doc"), Path.of("..", "docs")).flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".md") &&
                        !path.normalize().toString().replace('\\', '/').contains("/docs/superpowers/")
                }.map(Files::readString).toList()
            }
        }.joinToString("\n")
        val forbidden = listOf(
            Regex("openai\\.(?:api-key|base-url|model)", RegexOption.IGNORE_CASE),
            Regex("@Qualifier\\(\\\"llmRestTemplate\\\"\\)"),
            Regex("(?:api.?key|ApiKey)[^\\n]{0,40}(?:isBlank|空|未配置)[^\\n]{0,40}(?:演示|demo)", RegexOption.IGNORE_CASE),
            Regex("google\\.proxy-url[^\\n]{0,80}(?:驱动|控制|代理)[^\\n]{0,40}(?:llmRestTemplate|Agent|AI|OpenAI|翻译)", RegexOption.IGNORE_CASE),
            Regex("(?:Agent|AI|OpenAI|翻译|LLM)[^\\n]{0,80}(?:继承|共享|使用)[^\\n]{0,40}google\\.proxy-url", RegexOption.IGNORE_CASE),
            Regex("llmRestTemplate[^\\n]{0,80}google\\.proxy-url", RegexOption.IGNORE_CASE)
        )

        forbidden.forEach { pattern ->
            assertFalse(pattern.containsMatchIn(currentDocumentation), "obsolete documentation pattern: ${pattern.pattern}")
        }
    }
}
