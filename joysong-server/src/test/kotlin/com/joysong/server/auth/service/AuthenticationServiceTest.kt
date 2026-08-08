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

    private fun adminUser(deletedAt: LocalDateTime? = null) = UserEntity(
        id = "admin-id",
        phone = "+8613800000000",
        passwordHash = "admin-password-hash",
        nickname = "Admin",
        role = "ADMIN",
        deletedAt = deletedAt
    )
}
