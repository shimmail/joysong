package com.joysong.server.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class ConfigValidator(
    private val environment: Environment,
    @Value("\${jwt.secret:}") private val jwtSecret: String,
    @Value("\${google.client-id:}") private val googleClientId: String,
    @Value("\${oss.access-key-id:}") private val ossAccessKeyId: String,
    @Value("\${oss.access-key-secret:}") private val ossAccessKeySecret: String,
    @Value("\${oss.endpoint:}") private val ossEndpoint: String,
    @Value("\${oss.bucket-name:}") private val ossBucketName: String,
    @Value("\${aliyun.sms.access-key-id:}") private val smsAccessKeyId: String,
    @Value("\${aliyun.sms.access-key-secret:}") private val smsAccessKeySecret: String,
    @Value("\${aliyun.sms.sign-name:}") private val smsSignName: String,
    @Value("\${aliyun.sms.template-code:}") private val smsTemplateCode: String,
    @Value("\${spring.datasource.password:}") private val dbPassword: String,
    @Value("\${admin.bootstrap.phone:}") private val adminPhone: String,
    @Value("\${admin.bootstrap.password:}") private val adminPassword: String,
    @Value("\${oss.enabled:false}") private val ossEnabled: Boolean,
    @Value("\${aliyun.sms.enabled:false}") private val smsEnabled: Boolean,
    private val aiAgentProperties: AiAgentProperties,
    @Value("\${security.verification-code.log-for-dev:false}") private val logVerificationCodeForDev: Boolean,
    @Value("\${seed.demo.enabled:false}") private val demoSeedEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(ConfigValidator::class.java)

    @PostConstruct
    fun validate() {
        val missing = mutableListOf<String>()

        if (jwtSecret.length < 32) missing.add("JWT_SECRET (at least 32 characters)")
        if (googleClientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
        if (ossEnabled && ossAccessKeyId.isBlank()) missing.add("OSS_ACCESS_KEY_ID")
        if (ossEnabled && ossAccessKeySecret.isBlank()) missing.add("OSS_ACCESS_KEY_SECRET")
        if (ossEnabled && ossEndpoint.isBlank()) missing.add("OSS_ENDPOINT")
        if (ossEnabled && ossBucketName.isBlank()) missing.add("OSS_BUCKET_NAME")
        if (smsEnabled && smsAccessKeyId.isBlank()) missing.add("SMS_ACCESS_KEY_ID")
        if (smsEnabled && smsAccessKeySecret.isBlank()) missing.add("SMS_ACCESS_KEY_SECRET")
        if (smsEnabled && smsSignName.isBlank()) missing.add("SMS_SIGN_NAME")
        if (smsEnabled && smsTemplateCode.isBlank()) missing.add("SMS_TEMPLATE_CODE")
        if (dbPassword.isBlank()) missing.add("DB_PASSWORD")
        if (!adminPhone.matches(Regex("^1\\d{10}$"))) missing.add("ADMIN_PHONE (valid mobile number)")
        if (adminPassword.length !in 12..128) missing.add("ADMIN_PASSWORD (12-128 characters)")
        if (!AiAgentProxyUrlPolicy.isAllowed(aiAgentProperties.proxyUrl)) {
            missing.add("OPENAI_PROXY_URL (http, https, or socks URL with host and explicit valid port; user-info is not allowed)")
        }

        val isProduction = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
        if (isProduction && !ossEnabled) missing.add("OSS_ENABLED=true")
        if (isProduction && !smsEnabled) missing.add("SMS_ENABLED=true")
        if (isProduction && aiAgentProperties.demoFallbackEnabled) {
            missing.add("OPENAI demo fallback must be disabled in production")
        }
        if (isProduction && aiAgentProperties.enabled) {
            if (aiAgentProperties.apiKey.isBlank()) missing.add("OPENAI_API_KEY")
            if (!OpenAiBaseUrlPolicy.isAllowed(aiAgentProperties.baseUrl)) {
                missing.add("OPENAI_BASE_URL (approved HTTPS endpoint required)")
            }
            if (aiAgentProperties.model.isBlank()) missing.add("AI_AGENT_MODEL")
        }
        if (isProduction && logVerificationCodeForDev) {
            missing.add("verification-code log-for-dev must be disabled in production")
        }
        if (isProduction && demoSeedEnabled) {
            missing.add("demo seed must be disabled in production")
        }

        if (missing.isNotEmpty()) {
            val message = "Missing required environment variables: ${missing.joinToString(", ")}. " +
                "Please set them via environment variables or application-dev.yml profile."
            logger.error(message)
            throw IllegalStateException(message)
        }

        logger.info("All required configuration values are present.")
    }
}
