package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
    private val adminAccountCommandService = mockk<AdminAccountCommandService>()
    private val service = UserProfileService(
        userRepository,
        diaryRepository,
        passwordEncoder,
        verificationCodeService,
        refreshTokenService,
        adminAccountCommandService,
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
    fun `administrator account deletion is rejected before every side effect`() {
        val admin = user(id = "admin", phone = "13900000000", role = "ADMIN")
        every {
            adminAccountCommandService.requireOrdinaryAccountDeletionAllowed(admin.id)
        } throws IllegalArgumentException("管理员不能通过普通用户接口注销")

        assertThrows(IllegalArgumentException::class.java) {
            service.deleteAccount(admin.id)
        }

        verify(exactly = 0) { diaryRepository.findByUserId(any()) }
        verify(exactly = 0) { diaryRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.revokeAll(any()) }
        verify(exactly = 0) { userRepository.deleteById(any()) }
    }

    @Test
    fun `ordinary user account deletion keeps diary token and soft-delete contract`() {
        val user = user(id = "ordinary-user", role = "USER")
        val firstDiary = DiaryEntity(
            id = "diary-1",
            title = "First",
            userId = user.id,
            authorName = "Original",
            authorAvatar = "avatar-1",
        )
        val secondDiary = DiaryEntity(
            id = "diary-2",
            title = "Second",
            userId = user.id,
            authorName = "Original",
            authorAvatar = "avatar-2",
        )
        every { adminAccountCommandService.requireOrdinaryAccountDeletionAllowed(user.id) } returns user
        every { diaryRepository.findByUserId(user.id) } returns listOf(firstDiary, secondDiary)
        every { diaryRepository.save(any()) } answers { firstArg() }
        every { userRepository.deleteById(user.id) } returns Unit

        service.deleteAccount(user.id)

        verifyOrder {
            adminAccountCommandService.requireOrdinaryAccountDeletionAllowed(user.id)
            diaryRepository.findByUserId(user.id)
            diaryRepository.save(match { it.id == firstDiary.id && it.authorName == "已注销用户" && it.authorAvatar.isEmpty() })
            diaryRepository.save(match { it.id == secondDiary.id && it.authorName == "已注销用户" && it.authorAvatar.isEmpty() })
            refreshTokenService.revokeAll(user.id)
            userRepository.deleteById(user.id)
        }
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
    fun `admin method signatures delegate to the command boundary`() {
        val user = user(id = "user", role = "USER")
        val deleted = user.copy(deletedAt = LocalDateTime.now())
        every { adminAccountCommandService.updateRole(user.id, "ADMIN") } returns user.copy(role = "ADMIN")
        every { adminAccountCommandService.deactivate(user.id) } returns (true to "success")
        every { adminAccountCommandService.reactivate(user.id) } returns deleted.copy(deletedAt = null)

        assertEquals("ADMIN", service.adminUpdateRole(user.id, "ADMIN")?.role)
        assertEquals(true to "success", service.adminDeactivate(user.id))
        assertEquals(null, service.adminReactivate(user.id)?.deletedAt)
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
