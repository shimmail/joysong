package com.joysong.server.auth.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.security.SecureRandom

object VerificationCodePolicy {
    const val EXPIRE_SECONDS = 300L
    const val RESEND_INTERVAL_SECONDS = 60L
    const val MAX_FAILED_ATTEMPTS = 5
}

enum class VerificationCodeDeliveryFailure {
    SEND_FAILED,
    PROVIDER_UNAVAILABLE
}

class VerificationCodeDeliveryException(
    val failure: VerificationCodeDeliveryFailure,
    message: String
) : IllegalStateException(message)

@Service
class VerificationCodeDeliveryService(
    private val aliyunSmsService: AliyunSmsService,
    private val environment: Environment,
    @Value("\${security.verification-code.log-for-dev:false}")
    private val logVerificationCodeForDev: Boolean
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    fun generateCode(): String = (secureRandom.nextInt(900000) + 100000).toString()

    fun deliver(phone: String, code: String) {
        if (aliyunSmsService.sendVerificationCode(phone, code)) {
            log.info("[SMS] 验证码已发送至 {}", maskPhone(phone))
            return
        }
        if (aliyunSmsService.isSmsEnabled()) {
            throw VerificationCodeDeliveryException(
                VerificationCodeDeliveryFailure.SEND_FAILED,
                "验证码发送失败，请稍后重试"
            )
        }
        if (isDevelopmentFallbackEnabled()) {
            log.info("[SMS-DEV] 验证码已生成并发送至 {}", maskPhone(phone))
            log.warn("[SMS-DEV] 本地测试验证码：{}", code)
            return
        }
        throw VerificationCodeDeliveryException(
            VerificationCodeDeliveryFailure.PROVIDER_UNAVAILABLE,
            "SMS_PROVIDER_UNAVAILABLE"
        )
    }

    private fun isDevelopmentFallbackEnabled(): Boolean {
        val activeProfiles = environment.activeProfiles
        return logVerificationCodeForDev &&
            activeProfiles.any { it.equals("dev", ignoreCase = true) } &&
            activeProfiles.none { it.equals("prod", ignoreCase = true) }
    }

    private fun maskPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)
}
