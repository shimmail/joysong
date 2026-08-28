package com.joysong.server.auth.service

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

enum class VerificationCodePurposeEnum { GENERAL, PHONE_CHANGE_CURRENT, PHONE_CHANGE_NEW }

private data class CodeEntry(
    val code: String,
    val expireAt: Instant,
    var failedAttempts: Int = 0
)

@Service
class VerificationCodeService(
    private val deliveryService: VerificationCodeDeliveryService
) {

    private val store = ConcurrentHashMap<String, CodeEntry>()
    private val lastSentAt = ConcurrentHashMap<String, Instant>()

    /** 生成并存储 6 位验证码，通过阿里云短信发送给用户手机 */
    fun generate(phone: String, purpose: VerificationCodePurposeEnum = VerificationCodePurposeEnum.GENERAL): String {
        require(phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) { "手机号格式不正确" }
        val now = Instant.now()
        val key = "$phone:$purpose"
        val previousSentAt = lastSentAt[key]
        if (previousSentAt != null && now.isBefore(previousSentAt.plusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS))) {
            throw IllegalArgumentException("请在60秒后再试")
        }
        val code = deliveryService.generateCode()
        deliveryService.deliver(phone, code)
        val entry = CodeEntry(code, now.plusSeconds(VerificationCodePolicy.EXPIRE_SECONDS))
        store[key] = entry
        lastSentAt[key] = now

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
            if (entry.failedAttempts >= VerificationCodePolicy.MAX_FAILED_ATTEMPTS) store.remove(key)
            return false
        }
        store.remove(key)
        return true
    }
}
