package com.joysong.server.auth.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerificationCodeServiceTest {

    private val smsService = mockk<AliyunSmsService>()

    @Test
    fun `production mode rejects an unsent verification code`() {
        every { smsService.sendVerificationCode(any(), any()) } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = VerificationCodeService(
            aliyunSmsService = smsService,
            logVerificationCodeForDev = false
        )

        val error = assertThrows(IllegalStateException::class.java) {
            service.generate("+8613800138000")
        }

        assertTrue(error.message == "SMS_PROVIDER_UNAVAILABLE")
        assertFalse(service.validate("+8613800138000", "000000"))
    }

    @Test
    fun `development mode keeps the logged code usable`() {
        every { smsService.sendVerificationCode(any(), any()) } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = VerificationCodeService(
            aliyunSmsService = smsService,
            logVerificationCodeForDev = true
        )

        val code = service.generate("+8613800138000")

        assertTrue(service.validate("+8613800138000", code))
        assertFalse(service.validate("+8613800138000", code))
    }

    @Test
    fun `enabled provider failure invalidates the code`() {
        every { smsService.sendVerificationCode(any(), any()) } returns false
        every { smsService.isSmsEnabled() } returns true
        val service = VerificationCodeService(
            aliyunSmsService = smsService,
            logVerificationCodeForDev = false
        )

        assertThrows(IllegalStateException::class.java) {
            service.generate("+8613800138000")
        }
        assertFalse(service.validate("+8613800138000", "000000"))
    }
}
