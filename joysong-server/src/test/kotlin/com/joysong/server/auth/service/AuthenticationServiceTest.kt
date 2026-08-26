package com.joysong.server.auth.service

import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.LocalDateTime
import java.util.Optional

class AuthenticationServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>(relaxed = true)
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val verificationCodeService = mockk<VerificationCodeService>()
    private val service = AuthenticationService(
        userRepository = userRepository,
        passwordEncoder = passwordEncoder,
        refreshTokenService = refreshTokenService,
        verificationCodeService = verificationCodeService,
        googleClientId = "test-client-id",
        googleProxyUrl = ""
    )

    @Test
    fun `loginWithCode rejects active admin account`() {
        val admin = adminUser()
        every { verificationCodeService.validate(admin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneIncludeDeleted(admin.phone!!) } returns Optional.of(admin)

        assertThrows(BadCredentialsException::class.java) {
            service.loginWithCode(admin.phone!!, "123456")
        }

        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginWithCode rejects deleted admin without reactivating account`() {
        val admin = adminUser(deletedAt = LocalDateTime.now())
        every { verificationCodeService.validate(admin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneIncludeDeleted(admin.phone!!) } returns Optional.of(admin)
        every { userRepository.save(any()) } answers { firstArg() }

        assertThrows(BadCredentialsException::class.java) {
            service.loginWithCode(admin.phone!!, "123456")
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginWithCode continues to issue tokens for active user`() {
        val user = UserEntity(
            id = "user-id",
            phone = "+8613800000001",
            passwordHash = "",
            nickname = "User"
        )
        every { verificationCodeService.validate(user.phone!!, "123456") } returns true
        every { userRepository.findByPhoneIncludeDeleted(user.phone!!) } returns Optional.of(user)
        every { refreshTokenService.issue(user.id, user.phone!!, "USER") } returns IssuedTokens(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            accessTokenExpiresIn = 3600
        )

        val response = service.loginWithCode(user.phone!!, "123456")

        assertEquals("access-token", response.accessToken)
        assertEquals("refresh-token", response.refreshToken)
        assertEquals("USER", response.user.role)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `loginAdmin issues tokens for a valid administrator`() {
        val admin = adminUser()
        every { userRepository.findByPhone(admin.phone!!) } returns Optional.of(admin)
        every { passwordEncoder.matches("StrongAdminPassword!1", admin.passwordHash) } returns true
        every { refreshTokenService.issue(admin.id, admin.phone!!, "ADMIN") } returns IssuedTokens(
            accessToken = "admin-access-token",
            refreshToken = "admin-refresh-token",
            accessTokenExpiresIn = 28_800
        )

        val response = service.loginAdmin(admin.phone!!, "StrongAdminPassword!1")

        assertEquals("admin-access-token", response.accessToken)
        assertEquals("admin-refresh-token", response.refreshToken)
        assertEquals("ADMIN", response.user.role)
    }

    @Test
    fun `loginAdmin rejects a non administrator even when the password matches`() {
        val user = adminUser().copy(id = "user-id", role = "USER")
        every { userRepository.findByPhone(user.phone!!) } returns Optional.of(user)
        every { passwordEncoder.matches("CorrectUserPassword!1", user.passwordHash) } returns true

        assertThrows(BadCredentialsException::class.java) {
            service.loginAdmin(user.phone!!, "CorrectUserPassword!1")
        }

        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginAdmin rejects an administrator with the same generic exception for a wrong password`() {
        val admin = adminUser()
        every { userRepository.findByPhone(admin.phone!!) } returns Optional.of(admin)
        every { passwordEncoder.matches("WrongPassword!1", admin.passwordHash) } returns false

        val error = assertThrows(BadCredentialsException::class.java) {
            service.loginAdmin(admin.phone!!, "WrongPassword!1")
        }

        assertEquals("管理员账号或密码错误", error.message)
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `logout revokes the supplied refresh token`() {
        service.logout("refresh-token")

        verify(exactly = 1) { refreshTokenService.revoke("refresh-token") }
    }

    private fun adminUser(deletedAt: LocalDateTime? = null) = UserEntity(
        id = "admin-id",
        phone = "+8613800000000",
        passwordHash = "admin-password-hash",
        nickname = "Admin",
        role = "ADMIN",
        deletedAt = deletedAt
    )
}
