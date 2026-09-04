package com.joysong.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.joysong.server.translation.config.TranslationConfiguration
import com.joysong.server.translation.config.TranslationProperties
import com.joysong.server.translation.service.TranslationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.StandardEnvironment

class AiAgentProfileStartupTest {

    private val contextRunner = ApplicationContextRunner()
        .withInitializer { context ->
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            context.environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            context.environment.setActiveProfiles("prod")
        }
        .withInitializer(ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration::class.java))
        .withUserConfiguration(ProfileStartupConfiguration::class.java)
        .withPropertyValues(
            "spring.datasource.url=jdbc:mysql://example.test/joysong",
            "spring.datasource.username=test-user",
            "spring.datasource.password=test-password",
            "jwt.secret=test-jwt-secret-that-is-at-least-32-characters",
            "google.client-id=test-google-client-id",
            "admin.bootstrap.phone=13800000000",
            "admin.bootstrap.password=test-admin-password",
            "app.share-base-url=https://share.example.test/s/diary/",
            "oss.enabled=true",
            "oss.endpoint=https://oss-cn-hangzhou-internal.aliyuncs.com",
            "oss.region=cn-hangzhou",
            "oss.bucket-name=test-bucket",
            "oss.private-bucket-name=test-private-bucket",
            "oss.public-base-url=https://test-bucket.oss-cn-hangzhou.aliyuncs.com",
            "oss.credential-mode=static",
            "oss.access-key-id=test-oss-key",
            "oss.access-key-secret=test-oss-secret",
            "private-storage.mode=oss",
            "aliyun.sms.enabled=true",
            "aliyun.sms.access-key-id=test-sms-key",
            "aliyun.sms.access-key-secret=test-sms-secret",
            "aliyun.sms.sign-name=test-sign",
            "aliyun.sms.template-code=test-template",
            "ai-agent.provider=qwen",
            "ai-agent.api-key=test-key",
            "ai-agent.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
            "ai-agent.model=test-model",
            "ai-agent.intent-model=test-intent-model",
            "TRANSLATION_PROVIDER=qwen",
            "TRANSLATION_API_KEY=translation-key",
            "TRANSLATION_BASE_URL=https://translation.example.test/v1",
            "TRANSLATION_MODEL=translation-model"
        )

    @Test
    fun `production profile starts with independent agent and translation contracts`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.environment.activeProfiles).containsExactly("prod")
            assertThat(context.environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true")
            assertThat(context).hasSingleBean(TranslationService::class.java)
            val agentProperties = context.getBean(AiAgentProperties::class.java)
            val translationProperties = context.getBean(TranslationProperties::class.java)
            assertThat(agentProperties.enabled).isTrue()
            assertThat(translationProperties.apiKey).isEqualTo("translation-key")
            assertThat(translationProperties.baseUrl).isEqualTo("https://translation.example.test/v1")
            assertThat(translationProperties.model).isEqualTo("translation-model")
            assertThat(translationProperties.apiKey).isNotEqualTo(agentProperties.apiKey)
            assertThat(translationProperties.baseUrl).isNotEqualTo(agentProperties.baseUrl)
        }
    }

    @Test
    fun `enabled production profile fails without an explicitly configured model`() {
        contextRunner
            .withPropertyValues(
                "ai-agent.model="
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("AI_AGENT_MODEL")
            }
    }

    @Test
    fun `production profile fails with a blank OSS region`() {
        contextRunner
            .withPropertyValues("oss.region= ")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_REGION")
            }
    }

    @Test
    fun `production profile fails with a non-HTTPS OSS endpoint`() {
        contextRunner
            .withPropertyValues("oss.endpoint=http://oss-cn-hangzhou-internal.aliyuncs.com")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_ENDPOINT")
            }
    }

    @Test
    fun `production profile accepts ECS RAM role credentials without static access keys`() {
        contextRunner
            .withPropertyValues(
                "oss.credential-mode=ecs-ram-role",
                "oss.ecs-ram-role-name=joysong-demo-role",
                "oss.access-key-id=",
                "oss.access-key-secret=",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
            }
    }

    @Test
    fun `production profile rejects a blank ECS RAM role name`() {
        contextRunner
            .withPropertyValues(
                "oss.credential-mode=ecs-ram-role",
                "oss.ecs-ram-role-name=",
                "oss.access-key-id=",
                "oss.access-key-secret=",
            )
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_ECS_RAM_ROLE_NAME")
            }
    }

    @Test
    fun `production profile rejects an unsupported OSS credential mode`() {
        contextRunner
            .withPropertyValues("oss.credential-mode=workbench")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_CREDENTIAL_MODE")
            }
    }

    @Test
    fun `internal OSS endpoint requires an explicit public base URL`() {
        contextRunner
            .withPropertyValues("oss.public-base-url=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_PUBLIC_BASE_URL")
            }
    }

    @Test
    fun `OSS public base URL must use HTTPS`() {
        contextRunner
            .withPropertyValues("oss.public-base-url=http://test-bucket.oss-cn-hangzhou.aliyuncs.com")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_PUBLIC_BASE_URL")
            }
    }

    @Test
    fun `private OSS storage requires a dedicated private bucket`() {
        contextRunner
            .withPropertyValues("oss.private-bucket-name=")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains("OSS_PRIVATE_BUCKET_NAME")
            }
    }

    @Test
    fun `private and public media cannot share the same OSS bucket`() {
        contextRunner
            .withPropertyValues("oss.private-bucket-name=test-bucket")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain())
                    .contains("OSS_PRIVATE_BUCKET_NAME (must differ from OSS_BUCKET_NAME)")
            }
    }

    @Test
    fun `private OSS storage cannot run while OSS is disabled`() {
        contextRunner
            .withPropertyValues("oss.enabled=false")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain())
                    .contains("OSS_ENABLED=true (required for private OSS storage)")
            }
    }

    @Test
    fun `private storage rejects an unsupported mode`() {
        contextRunner
            .withPropertyValues("private-storage.mode=automatic")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain())
                    .contains("PRIVATE_FILE_STORAGE_MODE (local or oss)")
            }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "oss.endpoint=|OSS_ENDPOINT",
            "oss.bucket-name=|OSS_BUCKET_NAME",
            "oss.access-key-id=|OSS_ACCESS_KEY_ID",
            "oss.access-key-secret=|OSS_ACCESS_KEY_SECRET",
        ],
    )
    fun `production profile fails when a required OSS setting is blank`(
        propertyOverride: String,
        expectedVariable: String,
    ) {
        contextRunner
            .withPropertyValues(propertyOverride)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure.causeChain()).contains(expectedVariable)
            }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(
        AiAgentConfiguration::class,
        TranslationConfiguration::class,
        RestTemplateConfig::class,
        ConfigValidator::class,
        TranslationService::class
    )
    class ProfileStartupConfiguration {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
    }

    private fun Throwable?.causeChain(): String = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
}
