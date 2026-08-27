package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.user.entity.UserEntity
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
    private val diaryRepository = mockk<DiaryRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private val verificationCodeService = mockk<VerificationCodeService>()
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val service = UserProfileService(
        userRepository,
        diaryRepository,
        passwordEncoder,
        verificationCodeService,
        refreshTokenService
    )

    @Test
    fun `resetPassword stops invalid code before any account or credential work`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate("+8613800000001", "123456") } returns false
        every { userRepository.findByPhone(user.phone!!) } returns Optional.of(user)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.resetPassword("+8613800000001", "123456", "ValidPass1")
        }

        assertEquals("验证码无效或已过期", error.message)
        verify(exactly = 0) { userRepository.findByPhone(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword updates an active non admin after validating the code`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate(user.phone!!, "123456") } returns true
        every { userRepository.findByPhone(user.phone!!) } returns Optional.of(user)
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
        every { userRepository.findByPhone(admin.phone!!) } returns Optional.of(admin)
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
        every { userRepository.findByPhone(promotedAdmin.phone!!) } returns Optional.of(promotedAdmin)
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
    fun `resetPassword gives unknown and soft deleted targets the generic public error without writes`() {
        val unknownPhone = "+8613800000001"
        val softDeletedPhone = "+8613800000002"
        every { verificationCodeService.validate(unknownPhone, "123456") } returns true
        every { verificationCodeService.validate(softDeletedPhone, "123456") } returns true
        every { userRepository.findByPhone(unknownPhone) } returns Optional.empty()
        every { userRepository.findByPhone(softDeletedPhone) } returns Optional.empty()

        listOf(unknownPhone, softDeletedPhone).forEach { phone ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                service.resetPassword(phone, "123456", "ValidPass1")
            }
            assertEquals("验证码无效或已过期", error.message)
        }

        verify(exactly = 0) { userRepository.findByPhoneIncludeDeleted(any()) }
        verify(exactly = 0) { passwordEncoder.encode(any()) }
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `resetPassword consumes the code before rejecting an invalid user password`() {
        val user = user(role = "USER")
        every { verificationCodeService.validate(user.phone!!, "123456") } returns true
        every { userRepository.findByPhone(user.phone!!) } returns Optional.of(user)

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
    fun `adminUpdateRole rejects an international phone before promoting to ADMIN`() {
        val user = user(phone = "+8613800000001", role = "USER")
        every { userRepository.findById(user.id) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        assertThrows(IllegalArgumentException::class.java) {
            service.adminUpdateRole(user.id, "ADMIN")
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
    }

    @Test
    fun `adminUpdateRole allows an international administrator to be demoted`() {
        val admin = user(phone = "+8613800000001", role = "ADMIN")
        every { userRepository.findById(admin.id) } returns Optional.of(admin)
        every { userRepository.save(any()) } answers { firstArg() }

        val updated = service.adminUpdateRole(admin.id, "USER")

        assertEquals("USER", updated?.role)
        verify(exactly = 1) { refreshTokenService.revokeAll(admin.id) }
    }

    @Test
    fun `adminReactivate rejects an international administrator before saving`() {
        val admin = user(phone = "+8613800000001", role = "ADMIN", deletedAt = LocalDateTime.now())
        every { userRepository.findByIdIncludingDeleted(admin.id) } returns admin
        every { userRepository.save(any()) } answers { firstArg() }

        assertThrows(IllegalArgumentException::class.java) {
            service.adminReactivate(admin.id)
        }

        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `adminReactivate keeps international USER and professional accounts reactivatable`() {
        val user = user(id = "user-id", phone = "+8613800000001", role = "USER", deletedAt = LocalDateTime.now())
        val doctor = user(id = "doctor-id", phone = "+8613800000002", role = "DOCTOR", deletedAt = LocalDateTime.now())
        every { userRepository.findByIdIncludingDeleted(user.id) } returns user
        every { userRepository.findByIdIncludingDeleted(doctor.id) } returns doctor
        every { userRepository.save(any()) } answers { firstArg() }

        assertEquals(null, service.adminReactivate(user.id)?.deletedAt)
        assertEquals(null, service.adminReactivate(doctor.id)?.deletedAt)
        verify(exactly = 2) { userRepository.save(any()) }
    }

    private fun user(
        id: String = "user-id",
        phone: String = "+8613800000001",
        role: String,
        deletedAt: LocalDateTime? = null
    ) = UserEntity(
        id = id,
        phone = phone,
        passwordHash = "old-hash",
        nickname = "User",
        role = role,
        credentialsUpdatedAt = LocalDateTime.now().minusMinutes(1),
        deletedAt = deletedAt
    )
}
