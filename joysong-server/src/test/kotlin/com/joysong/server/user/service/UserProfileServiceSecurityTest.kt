package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.Optional

class UserProfileServiceSecurityTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val verificationCodeService = mockk<VerificationCodeService>()
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val adminAccountCommandService = mockk<AdminAccountCommandService>()
    private val accountLifecycleGuard = mockk<AccountLifecycleGuard>()
    private val service = UserProfileService(
        userRepository,
        passwordEncoder,
        verificationCodeService,
        refreshTokenService,
        adminAccountCommandService,
        accountLifecycleGuard,
    )

    @Test
    fun `resetPassword stops invalid code before any account or credential work`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate("+8613800000001", "123456") } returns false
        every { userRepository.findByPhoneForUpdate(user.phone!!) } returns user

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.resetPassword("+8613800000001", "123456", "ValidPass1")
        }

        assertEquals("验证码无效或已过期", error.message)
        verify(exactly = 0) { userRepository.findByPhoneForUpdate(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword updates an active non admin after validating the code`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate(user.phone!!, "123456") } returns true
        every { userRepository.findByPhoneForUpdate(user.phone!!) } returns user
        every { passwordEncoder.encode("ValidPass1") } returns "updated-hash"
        every { userRepository.save(any()) } answers { firstArg() }

        service.resetPassword(user.phone!!, "123456", "ValidPass1")

        verify(exactly = 1) { passwordEncoder.encode("ValidPass1") }
        verify(exactly = 1) {
            userRepository.save(match { it.passwordHash == "updated-hash" && it.credentialsUpdatedAt.isAfter(user.credentialsUpdatedAt) })
        }
        verify(exactly = 1) { refreshTokenService.revokeAll(user.id) }
    }

    @Test
    fun `resetPassword gives active administrator the generic public error without writes`() {
        val admin = user(role = "ADMIN")
        every { verificationCodeService.validate(admin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneForUpdate(admin.phone!!) } returns admin
        every { passwordEncoder.encode("StrongAdminPass1!") } returns "unexpected-hash"
        every { userRepository.save(any()) } answers { firstArg() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.resetPassword(admin.phone!!, "123456", "StrongAdminPass1!")
        }

        assertEquals("验证码无效或已过期", error.message)
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword gives a promoted administrator the generic public error without writes`() {
        val promotedAdmin = user(id = "promoted-admin-id", phone = "+8613800000002", role = "ADMIN")
        every { verificationCodeService.validate(promotedAdmin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneForUpdate(promotedAdmin.phone!!) } returns promotedAdmin
        every { passwordEncoder.encode("StrongAdminPass1!") } returns "unexpected-hash"
        every { userRepository.save(any()) } answers { firstArg() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.resetPassword(promotedAdmin.phone!!, "123456", "StrongAdminPass1!")
        }

        assertEquals("验证码无效或已过期", error.message)
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword gives unknown and erased targets the generic public error without writes`() {
        val unknownPhone = "+8613800000001"
        val erasedPhone = "+8613800000002"
        val erased = user(id = "erased", phone = erasedPhone, role = "USER", accountState = AccountState.ERASED)
        every { verificationCodeService.validate(unknownPhone, "123456") } returns true
        every { verificationCodeService.validate(erasedPhone, "123456") } returns true
        every { userRepository.findByPhoneForUpdate(unknownPhone) } returns null
        every { userRepository.findByPhoneForUpdate(erasedPhone) } returns erased

        listOf(unknownPhone, erasedPhone).forEach { phone ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                service.resetPassword(phone, "123456", "ValidPass1")
            }
            assertEquals("验证码无效或已过期", error.message)
        }

        verify(exactly = 1) { userRepository.findByPhoneForUpdate(unknownPhone) }
        verify(exactly = 1) { userRepository.findByPhoneForUpdate(erasedPhone) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword consumes the code before rejecting an invalid user password`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate(user.phone!!, "123456") } returns true
        every { userRepository.findByPhoneForUpdate(user.phone!!) } returns user

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.resetPassword(user.phone!!, "123456", "short")
        }

        assertEquals("密码长度应为 8-128 位", error.message)
        verify(exactly = 1) { verificationCodeService.validate(user.phone!!, "123456") }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `bootstrap phone change is rejected before verification or account writes`() {
        every {
            adminAccountCommandService.requireOrdinaryPhoneChangeAllowed("bootstrap", "+8613900000000")
        } throws IllegalArgumentException("Bootstrap 管理员不能修改引导手机号")

        assertThrows(IllegalArgumentException::class.java) {
            service.changePhone("bootstrap", "+8613900000000", "123456")
        }

        verify(exactly = 0) { verificationCodeService.validate(any(), any(), any()) }
        verify(exactly = 0) { userRepository.existsByPhone(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `phone binding rejects the E164 alias of the fixed administrator before account work`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            service.bindPhone("user-id", "+8613800000000", "123456")
        }

        assertEquals("该手机号为系统管理员保留号码", error.message)
        verify(exactly = 0) { accountLifecycleGuard.requireActiveForWrite(any()) }
        verify(exactly = 0) { userRepository.existsByPhone(any()) }
        verify(exactly = 0) { verificationCodeService.validate(any(), any()) }
    }

    @Test
    fun `new phone code rejects the E164 alias of the fixed administrator before account work`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            service.sendNewPhoneChangeCode("user-id", "+8613800000000")
        }

        assertEquals("该手机号为系统管理员保留号码", error.message)
        verify(exactly = 0) { userRepository.findById(any()) }
        verify(exactly = 0) { userRepository.existsByPhone(any()) }
        verify(exactly = 0) { verificationCodeService.generate(any(), any()) }
    }

    @Test
    fun `admin lifecycle methods delegate to the command boundary`() {
        val user = user(id = "user", role = "USER")
        val suspended = user.copy(accountState = AccountState.ADMIN_SUSPENDED)
        every { adminAccountCommandService.deactivate(user.id) } returns (true to "success")
        every { adminAccountCommandService.reactivate(user.id) } returns suspended.copy(accountState = AccountState.ACTIVE)

        assertEquals(true to "success", service.adminDeactivate(user.id))
        assertEquals(AccountState.ACTIVE, service.adminReactivate(user.id)?.accountState)
    }

    private fun user(
        id: String = "user-id",
        phone: String = "+8613800000001",
        role: String,
        accountState: AccountState = AccountState.ACTIVE
    ) = UserEntity(
        id = id,
        phone = phone,
        passwordHash = "old-hash",
        nickname = "User",
        role = role,
        credentialsUpdatedAt = LocalDateTime.now().minusMinutes(1),
        accountState = accountState
    )
}
