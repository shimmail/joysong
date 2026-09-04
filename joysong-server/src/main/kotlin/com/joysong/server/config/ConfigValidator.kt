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
    @Value("\${oss.public-base-url:}") private val ossPublicBaseUrl: String,
    @Value("\${oss.credential-mode:static}") private val ossCredentialMode: String,
    @Value("\${oss.ecs-ram-role-name:}") private val ossEcsRamRoleName: String,
    @Value("\${oss.private-bucket-name:}") private val ossPrivateBucketName: String,
    @Value("\${private-storage.mode:local}") private val privateStorageMode: String,
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
    @Value("\${payment.alipay-plus.simulated-enabled:false}")
    private val alipayPlusSimulatedEnabled: Boolean,
    @Value("\${payment.alipay-plus.auto-pay-on-order-create-enabled:false}")
    private val alipayPlusAutoPayOnOrderCreateEnabled: Boolean,
) {
    private val logger = LoggerFactory.getLogger(ConfigValidator::class.java)

    @PostConstruct
    fun validate() {
        val missing = mutableListOf<String>()
        val isProduction = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
        val isDevelopment = environment.activeProfiles.any { it.equals("dev", ignoreCase = true) }
        val isDemo = environment.activeProfiles.any { it.equals("demo", ignoreCase = true) }
        val normalizedOssBucketName = ossBucketName.trim()
        val normalizedPrivateBucketName = ossPrivateBucketName.trim()

        if (isProduction) {
            val unsafePaymentSettings = buildList {
                if (alipayPlusSimulatedEnabled) add("payment.alipay-plus.simulated-enabled")
                if (alipayPlusAutoPayOnOrderCreateEnabled) {
                    add("payment.alipay-plus.auto-pay-on-order-create-enabled")
                }
            }
            if (unsafePaymentSettings.isNotEmpty()) {
                val message = "Unsafe production payment configuration: " +
                    unsafePaymentSettings.joinToString(", ") { "$it must be false" }
                logger.error(message)
                throw IllegalStateException(message)
            }
        }

        if (jwtSecret.length < 32) missing.add("JWT_SECRET (at least 32 characters)")
        if (!isDemo && googleClientId.isBlank()) missing.add("GOOGLE_CLIENT_ID")
        if (ossEnabled) {
            if (ossEndpoint.isBlank()) missing.add("OSS_ENDPOINT")
            if (normalizedOssBucketName.isBlank()) missing.add("OSS_BUCKET_NAME")
            if (normalizedOssBucketName.isNotBlank() && !isValidOssBucketName(normalizedOssBucketName)) {
                missing.add("OSS_BUCKET_NAME (valid OSS bucket name required)")
            }
            if (ossRegion.isBlank()) missing.add("OSS_REGION")
            if (ossEndpoint.isNotBlank() && !isAliyunOssHttpsEndpointForRegion(ossEndpoint, ossRegion)) {
                missing.add("OSS_ENDPOINT (Alibaba Cloud OSS HTTPS endpoint matching OSS_REGION required)")
            }
            if (ossPublicBaseUrl.isNotBlank() && !isHttpsBaseUrl(ossPublicBaseUrl)) {
                missing.add("OSS_PUBLIC_BASE_URL (HTTPS URL without user info, query, or fragment required)")
            }
            if (isInternalOssEndpoint(ossEndpoint) && ossPublicBaseUrl.isBlank()) {
                missing.add("OSS_PUBLIC_BASE_URL (required with an internal OSS endpoint)")
            }
            when (ossCredentialMode.trim().lowercase()) {
                OSS_CREDENTIAL_MODE_STATIC -> {
                    if (ossAccessKeyId.isBlank()) missing.add("OSS_ACCESS_KEY_ID")
                    if (ossAccessKeySecret.isBlank()) missing.add("OSS_ACCESS_KEY_SECRET")
                }

                OSS_CREDENTIAL_MODE_ECS_RAM_ROLE -> {
                    if (ossEcsRamRoleName.isBlank()) missing.add("OSS_ECS_RAM_ROLE_NAME")
                }

                else -> missing.add("OSS_CREDENTIAL_MODE (static or ecs-ram-role)")
            }
        }
        when (privateStorageMode.trim().lowercase()) {
            PRIVATE_STORAGE_MODE_LOCAL -> Unit
            PRIVATE_STORAGE_MODE_OSS -> {
                if (!ossEnabled) missing.add("OSS_ENABLED=true (required for private OSS storage)")
                if (normalizedPrivateBucketName.isBlank()) missing.add("OSS_PRIVATE_BUCKET_NAME")
            }

            else -> missing.add("PRIVATE_FILE_STORAGE_MODE (local or oss)")
        }
        if (normalizedPrivateBucketName.isNotBlank() && !isValidOssBucketName(normalizedPrivateBucketName)) {
            missing.add("OSS_PRIVATE_BUCKET_NAME (valid OSS bucket name required)")
        }
        if (
            normalizedPrivateBucketName.isNotBlank() &&
            normalizedOssBucketName.isNotBlank() &&
            normalizedPrivateBucketName.equals(normalizedOssBucketName, ignoreCase = true)
        ) {
            missing.add("OSS_PRIVATE_BUCKET_NAME (must differ from OSS_BUCKET_NAME)")
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

    private fun isAliyunOssHttpsEndpointForRegion(value: String, region: String): Boolean = runCatching {
        val uri = URI(value.trim())
        val endpointRegion = OSS_ENDPOINT_HOST_PATTERN
            .matchEntire(uri.host.orEmpty().lowercase())
            ?.groupValues
            ?.get(1)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.port == -1 &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
            region.trim().isNotEmpty() &&
            endpointRegion == region.trim().lowercase()
    }.getOrDefault(false)

    private fun isValidOssBucketName(value: String): Boolean = OSS_BUCKET_NAME_PATTERN.matches(value)

    private fun isHttpsBaseUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }.getOrDefault(false)

    private fun isInternalOssEndpoint(value: String): Boolean = runCatching {
        URI(value.trim()).host?.contains("-internal.", ignoreCase = true) == true
    }.getOrDefault(false)

    private companion object {
        const val OSS_CREDENTIAL_MODE_STATIC = "static"
        const val OSS_CREDENTIAL_MODE_ECS_RAM_ROLE = "ecs-ram-role"
        const val PRIVATE_STORAGE_MODE_LOCAL = "local"
        const val PRIVATE_STORAGE_MODE_OSS = "oss"
        val OSS_BUCKET_NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$")
        val OSS_ENDPOINT_HOST_PATTERN = Regex(
            "^oss-([a-z0-9]+(?:-[a-z0-9]+)*?)(?:-internal)?\\.aliyuncs\\.com$",
        )
    }
}
