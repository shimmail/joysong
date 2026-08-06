package com.joysong.server.admin.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdminLoginAttemptServiceTest {

    @Test
    fun `达到失败阈值后锁定账号`() {
        val service = AdminLoginAttemptService(
            maxFailures = 2,
            lockDurationSeconds = 60,
            windowSeconds = 60
        )

        service.recordFailure("13800000000", "127.0.0.1")
        assertNull(service.retryAfterSeconds("13800000000", "127.0.0.1"))

        service.recordFailure("13800000000", "127.0.0.1")
        assertNotNull(service.retryAfterSeconds("13800000000", "127.0.0.1"))
    }

    @Test
    fun `成功登录后清除失败记录`() {
        val service = AdminLoginAttemptService(
            maxFailures = 1,
            lockDurationSeconds = 60,
            windowSeconds = 60
        )

        service.recordFailure("13800000000", "127.0.0.1")
        service.recordSuccess("13800000000", "127.0.0.1")

        assertNull(service.retryAfterSeconds("13800000000", "127.0.0.1"))
    }
}
