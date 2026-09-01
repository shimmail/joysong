package com.joysong.server.config

import com.joysong.server.diary.service.DiaryShareUrlPolicy
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

@Component
class ConfigValidator(
    private val environment: Environment,
    @Value("\${jwt.secret:}") private val jwtSecret: String,
    @Value("\${google.client-id:}") private val googleClientId: String,
    @Value("\${oss.access-key-id:}") private val ossAccessKeyId: String,
    @Value("\${oss.access-key-secret:}") private val ossAccessKeySecret: String,
    @Value("\${oss.endpoint:}") private val ossEndpoint: String,
    @Value("\${oss.bucket-name:}") private val ossBucketName: String,
    @Value("\${oss.region:}") private val ossRegion: String,
    @Value("\${aliyun.sms.access-key-id:}") private val smsAccessKeyId: String,
    @Value("\${aliyun.sms.access-key-secret:}") private val smsAccessKeySecret: String,
    @Value("\${aliyun.sms.sign-name:}") private val smsSignName: String,
    @Value("\${aliyun.sms.template-code:}") private val smsTemplateCode: String,
    @Value("\${spring.datasource.password:}") private val dbPassword: String,
    @Value("\${admin.bootstrap.phone:}") private val adminPhone: String,
    @Value("\${oss.enabled:false}") private val ossEnabled: Boolean,
    @Value("\${aliyun.sms.enabled:false}") private val smsEnabled: Boolean,
    private val aiAgentProperties: AiAgentProperties,
    @Value("\${security.verification-code.log-for-dev:false}") private val logVerificationCodeForDev: Boolean,
    @Value("\${app.share-base-url:}") private val shareBaseUrl: String,
    @Value("\${app.share-request-origin-fallback-enabled:false}")
    private val shareRequestOriginFallbackEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(ConfigValidator::class.java)

    @PostConstruct
    fun validate() {
        val missing = mutableListOf<String>()
        val isProduction = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
        val isDevelopment = environment.activeProfiles.any { it.equals("dev", ignoreCase = true) }

        if (jwtSecret.length < 32) missing.add("JWT_SECRET (at least 32 characters)")
        if (googleClientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
        if (ossEnabled && ossAccessKeyId.isBlank()) missing.add("OSS_ACCESS_KEY_ID")
        if (ossEnabled && ossAccessKeySecret.isBlank()) missing.add("OSS_ACCESS_KEY_SECRET")
        if (ossEnabled && ossEndpoint.isBlank()) missing.add("OSS_ENDPOINT")
        if (ossEnabled && ossBucketName.isBlank()) missing.add("OSS_BUCKET_NAME")
        if (ossEnabled && ossRegion.isBlank()) missing.add("OSS_REGION")
        if (isProduction && ossEnabled && ossEndpoint.isNotBlank() && !isCanonicalHttpsUrl(ossEndpoint)) {
            missing.add("OSS_ENDPOINT (HTTPS URL required in production)")
        }
        if (smsEnabled && smsAccessKeyId.isBlank()) missing.add("SMS_ACCESS_KEY_ID")
        if (smsEnabled && smsAccessKeySecret.isBlank()) missing.add("SMS_ACCESS_KEY_SECRET")
        if (smsEnabled && smsSignName.isBlank()) missing.add("SMS_SIGN_NAME")
        if (smsEnabled && smsTemplateCode.isBlank()) missing.add("SMS_TEMPLATE_CODE")
        if (dbPassword.isBlank()) missing.add("DB_PASSWORD")
        if (!adminPhone.matches(Regex("^1\\d{10}$"))) missing.add("ADMIN_PHONE (valid mobile number)")
        if (isProduction && !ossEnabled) missing.add("OSS_ENABLED=true")
        if (isProduction && !smsEnabled) missing.add("SMS_ENABLED=true")
        if (isProduction && shareBaseUrl.isBlank()) missing.add("APP_SHARE_BASE_URL")
        if (shareBaseUrl.isNotBlank() && !DiaryShareUrlPolicy.isValidBaseUrl(shareBaseUrl)) {
            missing.add("APP_SHARE_BASE_URL (absolute HTTP(S) URL without user info, query, or fragment)")
        }
        if (shareRequestOriginFallbackEnabled && (!isDevelopment || isProduction)) {
            missing.add("share request-origin fallback may only be enabled in the dev profile")
        }
        val provider = aiAgentProperties.provider
        if (aiAgentProperties.enabled && provider != null && provider != AiAgentProvider.QWEN) {
            missing.add("AI_AGENT_PROVIDER=QWEN (required by the complete deployment)")
        }
        val normalizedBaseUrl = provider?.let {
            AiAgentProviderUrlPolicy.normalizeAllowed(it, aiAgentProperties.baseUrl)
        }
        if (normalizedBaseUrl == null) {
            missing.add("AI_AGENT_BASE_URL (approved provider base endpoint required)")
        } else {
            aiAgentProperties.baseUrl = normalizedBaseUrl
        }
        if (isProduction) {
            if (provider == null) missing.add("AI_AGENT_PROVIDER")
            if (aiAgentProperties.apiKey.isBlank()) missing.add("AI_AGENT_API_KEY")
            if (aiAgentProperties.model.isBlank()) missing.add("AI_AGENT_MODEL")
            if (aiAgentProperties.intentModel.isBlank()) missing.add("AI_AGENT_INTENT_MODEL")
        }
        if (isProduction && logVerificationCodeForDev) {
            missing.add("verification-code log-for-dev must be disabled in production")
        }
        if (missing.isNotEmpty()) {
            val message = "Missing required environment variables: ${missing.joinToString(", ")}. " +
                "Please set them via environment variables or application-dev.yml profile."
            logger.error(message)
            throw IllegalStateException(message)
        }

        logger.info("All required configuration values are present.")
    }

    private fun isCanonicalHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    }.getOrDefault(false)
}
