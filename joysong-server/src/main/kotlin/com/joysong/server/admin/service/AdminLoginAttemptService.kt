package com.joysong.server.admin.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 单节点登录防爆破保护。分别限制账号和来源地址，避免攻击者只轮换其中一项绕过限制。
 * 多实例部署时应将此状态迁移到 Redis 等共享存储。
 */
@Service
class AdminLoginAttemptService(
    @Value("\${security.admin-login.max-failures:5}") private val maxFailures: Int,
    @Value("\${security.admin-login.lock-duration-seconds:900}") private val lockDurationSeconds: Long,
    @Value("\${security.admin-login.window-seconds:900}") private val windowSeconds: Long
) {
    private data class Attempt(
        var failures: Int,
        var windowStartedAt: Instant,
        var blockedUntil: Instant? = null
    )

    private val attempts = mutableMapOf<String, Attempt>()

    @Synchronized
    fun retryAfterSeconds(phone: String, clientAddress: String): Long? {
        val now = Instant.now()
        cleanup(now)
        return listOf(accountKey(phone), addressKey(clientAddress))
            .mapNotNull { attempts[it]?.blockedUntil }
            .filter { it.isAfter(now) }
            .maxOrNull()
            ?.let { Duration.between(now, it).seconds.coerceAtLeast(1) }
    }

    @Synchronized
    fun recordFailure(phone: String, clientAddress: String) {
        val now = Instant.now()
        increment(accountKey(phone), maxFailures, now)
        increment(addressKey(clientAddress), maxFailures * 4, now)
    }

    @Synchronized
    fun recordSuccess(phone: String, clientAddress: String) {
        attempts.remove(accountKey(phone))
        attempts.remove(addressKey(clientAddress))
    }

    private fun increment(key: String, threshold: Int, now: Instant) {
        val current = attempts[key]
        val attempt = if (current == null || Duration.between(current.windowStartedAt, now).seconds >= windowSeconds) {
            Attempt(failures = 0, windowStartedAt = now)
        } else {
            current
        }
        attempt.failures += 1
        if (attempt.failures >= threshold.coerceAtLeast(1)) {
            attempt.blockedUntil = now.plusSeconds(lockDurationSeconds.coerceAtLeast(1))
        }
        attempts[key] = attempt
    }

    private fun cleanup(now: Instant) {
        attempts.entries.removeIf { (_, attempt) ->
            val blockExpired = attempt.blockedUntil?.isBefore(now) != false
            blockExpired && Duration.between(attempt.windowStartedAt, now).seconds >= windowSeconds
        }
        if (attempts.size > 10_000) {
            attempts.entries
                .sortedBy { it.value.windowStartedAt }
                .take(attempts.size - 10_000)
                .forEach { attempts.remove(it.key) }
        }
    }

    private fun accountKey(phone: String) = "account:${phone.trim()}"
    private fun addressKey(clientAddress: String) = "address:$clientAddress"
}
