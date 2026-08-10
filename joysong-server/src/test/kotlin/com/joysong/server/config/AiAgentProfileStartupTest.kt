package com.joysong.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.joysong.server.translation.service.TranslationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.StandardEnvironment
import org.springframework.test.util.ReflectionTestUtils

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
            "oss.enabled=true",
            "oss.endpoint=oss.example.test",
            "oss.bucket-name=test-bucket",
            "oss.access-key-id=test-oss-key",
            "oss.access-key-secret=test-oss-secret",
            "aliyun.sms.enabled=true",
            "aliyun.sms.access-key-id=test-sms-key",
            "aliyun.sms.access-key-secret=test-sms-secret",
            "aliyun.sms.sign-name=test-sign",
            "aliyun.sms.template-code=test-template",
            "ai-agent.enabled=false"
        )

    @Test
    fun `disabled production profile starts without OpenAI provider credentials`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.environment.activeProfiles).containsExactly("prod")
            assertThat(context.environment.getProperty("spring.flyway.clean-disabled")).isEqualTo("true")
            assertThat(context).hasSingleBean(TranslationService::class.java)
            assertThat(context.getBean(AiAgentProperties::class.java).enabled).isFalse()
            val translationService = context.getBean(TranslationService::class.java)
            assertThat(ReflectionTestUtils.getField(translationService, "baseUrl")).isEqualTo("")
            assertThat(ReflectionTestUtils.getField(translationService, "restTemplate"))
                .isSameAs(context.getBean("llmRestTemplate"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(
        AiAgentConfiguration::class,
        RestTemplateConfig::class,
        ConfigValidator::class,
        TranslationService::class
    )
    class ProfileStartupConfiguration {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
    }
}
