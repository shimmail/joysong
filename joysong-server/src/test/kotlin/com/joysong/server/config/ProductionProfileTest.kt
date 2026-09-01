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
    fun `multipart limits accept five maximum-size refund evidence files`() {
        assertEquals("10MB", applicationProperties.getProperty("spring.servlet.multipart.max-file-size"))
        assertEquals("52MB", applicationProperties.getProperty("spring.servlet.multipart.max-request-size"))
        assertTrue(
            Files.readString(Path.of("deploy", "nginx", "joysong-api.conf"))
                .lineSequence()
                .any { it.trim() == "client_max_body_size 52m;" }
        )
    }

    @Test
    fun `production payment environment is selected by the Stripe API key`() {
        assertNull(properties.getProperty("payment.mode"))
        assertNull(properties.getProperty("payment.stripe.enabled"))
        assertEquals(false, properties.getProperty("security.verification-code.log-for-dev"))
        assertNull(properties.getProperty("openai.demo-fallback-enabled"))
    }

    @Test
    fun `production external services require environment backed configuration`() {
        assertEquals(true, properties.getProperty("oss.enabled"))
        assertEquals(true, properties.getProperty("aliyun.sms.enabled"))
        assertEquals("\${OSS_ENDPOINT}", properties.getProperty("oss.endpoint"))
        assertEquals("\${OSS_REGION}", properties.getProperty("oss.region"))
        assertEquals("\${OSS_BUCKET_NAME}", properties.getProperty("oss.bucket-name"))
        assertEquals("\${OSS_ACCESS_KEY_ID}", properties.getProperty("oss.access-key-id"))
        assertEquals("\${OSS_ACCESS_KEY_SECRET}", properties.getProperty("oss.access-key-secret"))
        assertEquals("\${SMS_SIGN_NAME}", properties.getProperty("aliyun.sms.sign-name"))
        assertTrue(properties.getProperty("aliyun.sms.template-code").toString().contains("SMS_TEMPLATE_CODE"))
        assertNull(properties.getProperty("openai.base-url"))
    }

    @Test
    fun `OSS region and HTTPS endpoint are exposed through the environment contract`() {
        assertEquals("\${OSS_ENDPOINT:}", applicationProperties.getProperty("oss.endpoint"))
        assertEquals("\${OSS_REGION:}", applicationProperties.getProperty("oss.region"))

        val environment = Files.readAllLines(Path.of(".env.example"))
            .filter { it.startsWith("OSS_") }
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }

        assertEquals("https://oss-cn-hangzhou.aliyuncs.com", environment["OSS_ENDPOINT"])
        assertEquals("cn-hangzhou", environment["OSS_REGION"])
    }

    @Test
    fun `diary share origin follows trusted proxy requests only in development`() {
        assertEquals("native", developmentProperties.getProperty("server.forward-headers-strategy"))
        assertEquals(
            "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1",
            developmentProperties.getProperty("server.tomcat.remoteip.internal-proxies")
        )
        assertEquals("\${APP_SHARE_BASE_URL:}", developmentProperties.getProperty("app.share-base-url"))
        assertEquals(true, developmentProperties.getProperty("app.share-request-origin-fallback-enabled"))
        assertEquals("\${APP_SHARE_BASE_URL}", properties.getProperty("app.share-base-url"))
        assertEquals(false, properties.getProperty("app.share-request-origin-fallback-enabled"))
    }

    @Test
    fun `production nginx routes diary share pages to the backend`() {
        assertTrue(
            Files.readAllLines(Path.of("deploy", "nginx", "joysong-api.conf"))
                .any { it.trim() == "location ^~ /s/diary/ {" }
        )
    }

    @Test
    fun `production nginx overwrites forwarded share origin headers`() {
        val nginx = Files.readString(Path.of("deploy", "nginx", "joysong-api.conf"))

        listOf("location /api/", "location ^~ /s/diary/").forEach { location ->
            val block = Regex(
                "${Regex.escape(location)}\\s*\\{([^}]*)}",
                RegexOption.DOT_MATCHES_ALL,
            ).find(nginx)?.groupValues?.get(1).orEmpty()

            assertTrue(block.contains("proxy_set_header X-Forwarded-Host \$host;"), location)
            assertTrue(block.contains("proxy_set_header X-Forwarded-Port \$server_port;"), location)
        }
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
    fun `development order auto payment is explicit and production stays disabled`() {
        assertEquals(
            "\${ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED:false}",
            applicationProperties.getProperty("payment.alipay-plus.auto-pay-on-order-create-enabled")
        )
        assertEquals(true, developmentProperties.getProperty("payment.alipay-plus.auto-pay-on-order-create-enabled"))
        assertEquals(false, properties.getProperty("payment.alipay-plus.auto-pay-on-order-create-enabled"))

        val environment = Files.readAllLines(Path.of(".env.example"))
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        assertEquals("false", environment["ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED"])
    }

    @Test
    fun `account deletion is default off production off and development explicit`() {
        assertEquals(
            "\${ACCOUNT_DELETION_ENABLED:false}",
            applicationProperties.getProperty("app.account-deletion.enabled"),
        )
        assertEquals(false, properties.getProperty("app.account-deletion.enabled"))
        assertEquals(false, properties.getProperty("app.account-deletion.allow-commerce-bypass"))
        assertEquals(true, developmentProperties.getProperty("app.account-deletion.enabled"))
        assertEquals(true, developmentProperties.getProperty("app.account-deletion.allow-commerce-bypass"))
        assertNull(developmentProperties.getProperty("app.account-deletion.dev-fixed-sms-code"))
        assertNull(properties.getProperty("app.account-deletion.dev-fixed-sms-code"))
        assertEquals(true, developmentProperties.getProperty("security.verification-code.log-for-dev"))
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
    fun `agent and translation runtime contracts expose independent environment variables`() {
        val yamlText = listOf("application.yml", "application-prod.yml", "application-dev.example.yml")
            .joinToString("\n") { ClassPathResource(it).inputStream.bufferedReader().use { reader -> reader.readText() } }
        val envText = Files.readString(Path.of(".env.example"))
        val expected = setOf(
            "AI_AGENT_PROVIDER",
            "AI_AGENT_API_KEY",
            "AI_AGENT_BASE_URL",
            "AI_AGENT_MODEL",
            "AI_AGENT_INTENT_MODEL",
            "TRANSLATION_PROVIDER",
            "TRANSLATION_API_KEY",
            "TRANSLATION_BASE_URL",
            "TRANSLATION_MODEL"
        )

        assertEquals(expected, environmentName.findAll(yamlText).map { it.value }.toSet())
        assertEquals(expected, environmentName.findAll(envText).map { it.value }.toSet())
        assertEquals("\${AI_AGENT_PROVIDER:}", applicationProperties.getProperty("ai-agent.provider"))
        assertEquals("\${AI_AGENT_API_KEY:}", applicationProperties.getProperty("ai-agent.api-key"))
        assertEquals("\${AI_AGENT_BASE_URL:}", applicationProperties.getProperty("ai-agent.base-url"))
        assertEquals("\${AI_AGENT_MODEL:}", applicationProperties.getProperty("ai-agent.model"))
        assertEquals("\${AI_AGENT_INTENT_MODEL:}", applicationProperties.getProperty("ai-agent.intent-model"))
        assertEquals("\${TRANSLATION_PROVIDER:qwen}", applicationProperties.getProperty("translation.provider"))
        assertEquals("\${TRANSLATION_API_KEY:}", applicationProperties.getProperty("translation.api-key"))
        assertEquals(
            "\${TRANSLATION_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}",
            applicationProperties.getProperty("translation.base-url")
        )
        assertEquals("\${TRANSLATION_MODEL:qwen3.7-flash}", applicationProperties.getProperty("translation.model"))
    }

    @Test
    fun `environment guard detects supported and legacy variable families regardless of case`() {
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

}
