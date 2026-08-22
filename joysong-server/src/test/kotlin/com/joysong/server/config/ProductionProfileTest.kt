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

    private val environmentName = Regex(
        "\\b(?:AI_AGENT|OPENAI|QWEN|TRANSLATION)_[A-Z0-9_]+\\b",
        RegexOption.IGNORE_CASE
    )

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
    fun `Stripe compatibility adapter defaults to legacy disabled`() {
        assertEquals(
            "\${STRIPE_LEGACY_ENABLED:false}",
            applicationProperties.getProperty("payment.stripe.legacy-enabled")
        )
    }

    @Test
    fun `Alipay Plus simulator is disabled by default and in production`() {
        assertEquals(
            "\${ALIPAY_PLUS_SIMULATED_ENABLED:false}",
            applicationProperties.getProperty("payment.alipay-plus.simulated-enabled")
        )
        assertEquals(false, properties.getProperty("payment.alipay-plus.simulated-enabled"))
    }

    @Test
    fun `development profile enables the Alipay Plus simulator`() {
        assertEquals(true, developmentProperties.getProperty("payment.alipay-plus.simulated-enabled"))
    }

    @Test
    fun `Stripe environment example is explicitly legacy and has no fake redirect defaults`() {
        val stripe = Files.readAllLines(Path.of(".env.example"))
            .filter { it.startsWith("STRIPE_") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

        assertEquals("false", stripe["STRIPE_LEGACY_ENABLED"])
        assertEquals("", stripe["STRIPE_SUCCESS_URL"])
        assertEquals("", stripe["STRIPE_CANCEL_URL"])
        assertEquals("Joysong legacy medical service", stripe["STRIPE_PRODUCT_NAME"])
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
        val expected = setOf(
            "AI_AGENT_PROVIDER",
            "AI_AGENT_API_KEY",
            "AI_AGENT_BASE_URL",
            "AI_AGENT_MODEL",
            "AI_AGENT_INTENT_MODEL"
        )

        assertEquals(expected, environmentName.findAll(yamlText).map { it.value }.toSet())
        assertEquals(expected, environmentName.findAll(envText).map { it.value }.toSet())
        val documentationWithoutMarkdownFileNames = deploymentDocumentation.replace(
            Regex("[A-Z0-9_]+\\.md", RegexOption.IGNORE_CASE),
            ""
        )
        assertEquals(
            expected,
            environmentName.findAll(documentationWithoutMarkdownFileNames).map { it.value }.toSet()
        )
        assertEquals("\${AI_AGENT_PROVIDER:}", applicationProperties.getProperty("ai-agent.provider"))
        assertEquals("\${AI_AGENT_API_KEY:}", applicationProperties.getProperty("ai-agent.api-key"))
        assertEquals("\${AI_AGENT_BASE_URL:}", applicationProperties.getProperty("ai-agent.base-url"))
        assertEquals("\${AI_AGENT_MODEL:}", applicationProperties.getProperty("ai-agent.model"))
        assertEquals("\${AI_AGENT_INTENT_MODEL:}", applicationProperties.getProperty("ai-agent.intent-model"))
    }

    @Test
    fun `agent environment guard catches legacy variables regardless of case`() {
        val sample = "openai_api_key OpenAI_BASE_URL qWeN_model translation_provider"

        assertEquals(
            setOf("openai_api_key", "OpenAI_BASE_URL", "qWeN_model", "translation_provider"),
            environmentName.findAll(sample).map { it.value }.toSet()
        )
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
            Regex("\\b(?:openai|qwen|translation)\\.(?:provider|api-key|base-url|model)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bai\\.provider\\b", RegexOption.IGNORE_CASE),
            Regex("\\bai-agent\\.(?:proxy-url|lease-timeout|intent-parser-enabled|demo-fallback-enabled|stream|reasoning)\\b", RegexOption.IGNORE_CASE),
            Regex("\\bllmRestTemplate\\b", RegexOption.IGNORE_CASE)
        )

        forbidden.forEach { pattern ->
            assertFalse(pattern.containsMatchIn(currentDocumentation), "obsolete documentation pattern: ${pattern.pattern}")
        }
    }

    @Test
    fun `current documentation describes the streaming contract without adding deployment variables`() {
        val developmentGuide = Files.readString(Path.of("..", "docs", "AI_AGENT_DEVELOPMENT.md"))
        val configurationGuide = Files.readString(Path.of("..", "docs", "CONFIGURATION_GUIDE.md"))
        val streamingContract = developmentGuide + "\n" + configurationGuide

        listOf(
            "/api/chat/sessions/{id}/messages/stream",
            "started",
            "delta",
            "completed",
            "error",
            "PLANNING",
            "partial"
        ).forEach { term ->
            assertTrue(streamingContract.contains(term), "streaming documentation is missing: $term")
        }
        assertTrue(streamingContract.contains("Qwen-only"), "streaming provider boundary must be explicit")
        assertTrue(streamingContract.contains("不持久化"), "error partial output must be documented as non-persistent")
    }

    @Test
    fun `current documentation requires a distinct nonblank production intent model`() {
        val currentDocuments = listOf(Path.of("..", "doc"), Path.of("..", "docs")).flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                        path.fileName.toString().endsWith(".md") &&
                        !path.normalize().toString().replace('\\', '/').contains("/docs/superpowers/")
                }.toList()
            }
        }
        val violations = currentDocuments.flatMap { path ->
            val content = Files.readString(path)
            findIntentModelDocumentationViolations(content).map { violation ->
                path.normalize().toString() to violation.trim()
            }
        }

        assertTrue(
            violations.isEmpty(),
            "AI_AGENT_INTENT_MODEL must be production-required and distinct: ${violations.joinToString()}"
        )
    }

    @Test
    fun `intent model documentation guard catches optional English placeholders and hard wraps`() {
        val violations = listOf(
            "AI_AGENT_INTENT_MODEL may be blank",
            "AI_AGENT_INTENT_MODEL may be empty",
            "AI_AGENT_INTENT_MODEL may be omitted",
            "AI_AGENT_INTENT_MODEL inherits AI_AGENT_MODEL",
            "AI_AGENT_INTENT_MODEL may be\nleft blank",
            "intent-model: \${AI_AGENT_INTENT_MODEL: }",
            "intent-model: \${AI_AGENT_INTENT_MODEL:\${AI_AGENT_MODEL}}"
        )

        violations.forEach { sample ->
            assertTrue(
                findIntentModelDocumentationViolations(sample).isNotEmpty(),
                "guard missed optional intent-model semantics: $sample"
            )
        }
    }

    @Test
    fun `intent model documentation guard accepts required and negated wording`() {
        val compliant = listOf(
            "AI_AGENT_INTENT_MODEL is required and must be set separately.",
            "AI_AGENT_INTENT_MODEL 不可选，必须单独配置。",
            "AI_AGENT_INTENT_MODEL 不允许留空。",
            "AI_AGENT_INTENT_MODEL 无回退，必须使用独立模型。"
        )

        compliant.forEach { sample ->
            assertTrue(
                findIntentModelDocumentationViolations(sample).isEmpty(),
                "guard rejected compliant intent-model semantics: $sample"
            )
        }
    }

    private fun findIntentModelDocumentationViolations(content: String): List<String> {
        val placeholderViolations = listOf(
            Regex("\\$\\{\\s*AI_AGENT_INTENT_MODEL\\s*:\\s*}", RegexOption.IGNORE_CASE),
            Regex(
                "\\$\\{\\s*AI_AGENT_INTENT_MODEL\\s*:\\s*\\$\\{\\s*AI_AGENT_MODEL\\s*}\\s*}",
                RegexOption.IGNORE_CASE
            )
        ).flatMap { pattern -> pattern.findAll(content).map { it.value }.toList() }

        val normalized = content.replace(Regex("\\s+"), " ").trim()
        val protected = normalized
            .replace(
                Regex(
                    "(?i)\\b(?:not\\s+optional|non-?optional|must\\s+not\\s+be\\s+(?:blank|empty|omitted)|" +
                        "cannot\\s+be\\s+(?:blank|empty|omitted)|non-?(?:blank|empty)|" +
                        "does\\s+not\\s+inherit|no\\s+fallback|does\\s+not\\s+fall\\s+back)\\b"
                ),
                "COMPLIANT"
            )
            .replace(
                Regex("不可选|并非可选|不允许留空|不得留空|不能留空|禁止留空|无回退|不会回退|不回退"),
                "合规"
            )
        val optionalSemantics = Regex(
            "(?i)optional|may\\s+be\\s+(?:blank|empty|omitted)|can\\s+be\\s+(?:blank|empty|omitted)|" +
                "(?:is\\s+)?(?:blank|empty)|omitted|inherits?(?:\\s+from)?|falls?\\s+back|fallback|" +
                "defaults?\\s+to|可选|留空|空值|回退|默认使用"
        )
        val semanticViolations = protected.split(Regex("[.!?。；;]")).mapNotNull { clause ->
            val variableIndex = clause.indexOf("AI_AGENT_INTENT_MODEL", ignoreCase = true)
            if (variableIndex < 0) return@mapNotNull null
            val start = (variableIndex - INTENT_MODEL_SEMANTIC_RADIUS).coerceAtLeast(0)
            val end = (variableIndex + "AI_AGENT_INTENT_MODEL".length + INTENT_MODEL_SEMANTIC_RADIUS)
                .coerceAtMost(clause.length)
            clause.substring(start, end).takeIf(optionalSemantics::containsMatchIn)?.trim()
        }

        return placeholderViolations + semanticViolations
    }

    private companion object {
        const val INTENT_MODEL_SEMANTIC_RADIUS = 96
    }
}
