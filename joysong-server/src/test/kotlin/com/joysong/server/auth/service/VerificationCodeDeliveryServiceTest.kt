package com.joysong.server.auth.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class VerificationCodeDeliveryServiceTest {

    private val smsService = mockk<AliyunSmsService>()

    @Test
    fun `allows an unsent code for the dev profile ignoring case when logging is enabled`() {
        every { smsService.sendVerificationCode("+8613800138000", "123456") } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = deliveryService(activeProfiles = arrayOf("DEV"), logForDev = true)

        assertDoesNotThrow {
            service.deliver("+8613800138000", "123456")
        }

        verify(exactly = 1) { smsService.sendVerificationCode("+8613800138000", "123456") }
    }

    @Test
    fun `fails closed when prod and dev profiles are both active`() {
        every { smsService.sendVerificationCode("+8613800138000", "123456") } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = deliveryService(activeProfiles = arrayOf("prod", "dev"), logForDev = true)

        val error = assertThrows(VerificationCodeDeliveryException::class.java) {
            service.deliver("+8613800138000", "123456")
        }

        assertEquals(VerificationCodeDeliveryFailure.PROVIDER_UNAVAILABLE, error.failure)
    }

    @Test
    fun `fails closed in dev when development logging is disabled`() {
        every { smsService.sendVerificationCode("+8613800138000", "123456") } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = deliveryService(activeProfiles = arrayOf("dev"), logForDev = false)

        val error = assertThrows(VerificationCodeDeliveryException::class.java) {
            service.deliver("+8613800138000", "123456")
        }

        assertEquals(VerificationCodeDeliveryFailure.PROVIDER_UNAVAILABLE, error.failure)
    }

    @Test
    fun `fails closed outside dev even when development logging is enabled`() {
        every { smsService.sendVerificationCode("+8613800138000", "123456") } returns false
        every { smsService.isSmsEnabled() } returns false
        val service = deliveryService(activeProfiles = arrayOf("test"), logForDev = true)

        val error = assertThrows(VerificationCodeDeliveryException::class.java) {
            service.deliver("+8613800138000", "123456")
        }

        assertEquals("SMS_PROVIDER_UNAVAILABLE", error.message)
        assertEquals(VerificationCodeDeliveryFailure.PROVIDER_UNAVAILABLE, error.failure)
    }

    @Test
    fun `reports a typed failure when an enabled provider cannot send`() {
        every { smsService.sendVerificationCode("+8613800138000", "123456") } returns false
        every { smsService.isSmsEnabled() } returns true
        val service = deliveryService(activeProfiles = arrayOf("dev"), logForDev = true)

        val error = assertThrows(VerificationCodeDeliveryException::class.java) {
            service.deliver("+8613800138000", "123456")
        }

        assertEquals("验证码发送失败，请稍后重试", error.message)
        assertEquals(VerificationCodeDeliveryFailure.SEND_FAILED, error.failure)
    }

    @Test
    fun `generates six digit codes within the allowed range`() {
        val service = deliveryService(activeProfiles = emptyArray(), logForDev = false)

        repeat(20) {
            val code = service.generateCode()

            assertTrue(code.matches(Regex("^[1-9]\\d{5}$")))
            assertTrue(code.toInt() in 100000..999999)
        }
    }

    private fun deliveryService(activeProfiles: Array<String>, logForDev: Boolean) =
        VerificationCodeDeliveryService(
            aliyunSmsService = smsService,
            environment = MockEnvironment().apply { setActiveProfiles(*activeProfiles) },
            logVerificationCodeForDev = logForDev
        )
}
