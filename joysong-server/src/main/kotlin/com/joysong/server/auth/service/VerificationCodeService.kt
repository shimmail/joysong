package com.joysong.server.auth.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class VerificationCodePurposeEnum { GENERAL, PHONE_CHANGE_CURRENT, PHONE_CHANGE_NEW }

private data class CodeEntry(
    val code: String,
    val expireAt: Instant,
    var failedAttempts: Int = 0
)

@Service
class VerificationCodeService(
    private val aliyunSmsService: AliyunSmsService,
    @Value("\${security.verification-code.log-for-dev:false}")
    private val logVerificationCodeForDev: Boolean
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val store = ConcurrentHashMap<String, CodeEntry>()
    private val secureRandom = SecureRandom()
    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    companion object {
        private const val EXPIRE_SECONDS = 300L
        private const val RESEND_INTERVAL_SECONDS = 60L
        private const val MAX_FAILED_ATTEMPTS = 5
    }

    /** 生成并存储 6 位验证码，通过阿里云短信发送给用户手机 */
    fun generate(phone: String, purpose: VerificationCodePurposeEnum = VerificationCodePurposeEnum.GENERAL): String {
        require(phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) { "手机号格式不正确" }
        val now = Instant.now()
        val key = "$phone:$purpose"
        val previousSentAt = lastSentAt[key]
        if (previousSentAt != null && now.isBefore(previousSentAt.plusSeconds(RESEND_INTERVAL_SECONDS))) {
            throw IllegalArgumentException("请在60秒后再试")
        }
        val code = (secureRandom.nextInt(900000) + 100000).toString()
        val entry = CodeEntry(code, now.plusSeconds(EXPIRE_SECONDS))
        store[key] = entry
        lastSentAt[key] = now

        // 尝试通过阿里云短信服务发送验证码
        val sent = aliyunSmsService.sendVerificationCode(phone, code)
        if (sent) {
            log.info("[SMS] 验证码已发送至 {}", maskPhone(phone))
        } else if (aliyunSmsService.isSmsEnabled()) {
            // 已启用的真实短信发送失败时，验证码不能继续生效，也不能误报发送成功。
            store.remove(key, entry)
            lastSentAt.remove(key, now)
            throw IllegalStateException("验证码发送失败，请稍后重试")
        } else {
            // Only an explicitly enabled local-development profile may keep
            // an unsent code alive. Production must fail closed instead of
            // reporting success for a verification code the user never got.
            if (!logVerificationCodeForDev) {
                store.remove(key, entry)
                lastSentAt.remove(key, now)
                throw IllegalStateException("SMS_PROVIDER_UNAVAILABLE")
            }
            log.info("[SMS-DEV] 验证码已生成并发送至 {}", maskPhone(phone))
            log.warn("[SMS-DEV] 本地测试验证码：{}", code)
        }

        return code
    }

    /** 验证验证码，验证通过后立即删除（一次性使用） */
    fun validate(phone: String, code: String, purpose: VerificationCodePurposeEnum = VerificationCodePurposeEnum.GENERAL): Boolean {
        val key = "$phone:$purpose"
        val entry = store[key] ?: return false
        if (Instant.now().isAfter(entry.expireAt)) {
            store.remove(key)
            return false
        }
        if (entry.code != code) {
            entry.failedAttempts += 1
            if (entry.failedAttempts >= MAX_FAILED_ATTEMPTS) store.remove(key)
            return false
        }
        store.remove(key)
        return true
    }

    private fun maskPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)
}
