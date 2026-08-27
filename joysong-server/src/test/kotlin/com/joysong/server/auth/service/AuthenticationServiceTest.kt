package com.joysong.server.auth.service

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
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
    fun `loginWithCode consumes a valid code then gives active admin the generic public error`() {
        val admin = adminUser()
        every { verificationCodeService.validate(admin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneIncludeDeleted(admin.phone!!) } returns Optional.of(admin)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.loginWithCode(admin.phone!!, "123456")
        }

        assertEquals("验证码无效或已过期", error.message)
        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginWithCode consumes a valid code then leaves deleted admin inactive`() {
        val admin = adminUser(deletedAt = LocalDateTime.now())
        every { verificationCodeService.validate(admin.phone!!, "123456") } returns true
        every { userRepository.findByPhoneIncludeDeleted(admin.phone!!) } returns Optional.of(admin)
        every { userRepository.save(any()) } answers { firstArg() }

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.loginWithCode(admin.phone!!, "123456")
        }

        assertEquals("验证码无效或已过期", error.message)
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
    fun `generic password login rejects administrator without checking the real hash or issuing tokens`() {
        val admin = adminUser()
        every { userRepository.findByPhone(admin.phone!!) } returns Optional.of(admin)
        every { passwordEncoder.matches("StrongAdminPassword!1", any()) } returns false

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.login(admin.phone!!, "StrongAdminPassword!1")
        }

        assertEquals("密码错误，请重试", error.message)
        verify(exactly = 0) { passwordEncoder.matches("StrongAdminPassword!1", admin.passwordHash) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `registration rejects active and deleted administrators without issuing tokens`() {
        val activeAdmin = adminUser()
        val deletedAdmin = adminUser(deletedAt = LocalDateTime.now())
        every { verificationCodeService.validate(activeAdmin.phone!!, "123456") } returns true
        every { userRepository.findByPhone(activeAdmin.phone!!) } returnsMany listOf(
            Optional.of(activeAdmin),
            Optional.empty()
        )
        every { userRepository.findByPhoneIncludeDeleted(activeAdmin.phone!!) } returns Optional.of(deletedAdmin)
        every { userRepository.save(any()) } answers { firstArg() }
        every { refreshTokenService.issue(activeAdmin.id, activeAdmin.phone!!, "ADMIN") } returns adminTokens()

        repeat(2) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                service.register(activeAdmin.phone!!, "123456", "NewAdminPassword!1")
            }
            assertEquals("手机号已注册", error.message)
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `google login rejects active and deleted administrators without issuing tokens`() {
        val email = "admin@example.com"
        val activeAdmin = adminUser().copy(email = email)
        val deletedAdmin = adminUser(deletedAt = LocalDateTime.now()).copy(email = email)
        val verifier = mockk<GoogleIdTokenVerifier>()
        val googleIdToken = mockk<GoogleIdToken>()
        val payload = mockk<GoogleIdToken.Payload>()
        stubGoogleVerifier(verifier)
        every { verifier.verify("google-id-token") } returns googleIdToken
        every { googleIdToken.payload } returns payload
        every { payload["email_verified"] } returns true
        every { payload.email } returns email
        every { payload["name"] } returns "Admin"
        every { payload["picture"] } returns null
        every { userRepository.findByEmailIncludeDeleted(email) } returnsMany listOf(
            Optional.of(activeAdmin),
            Optional.of(deletedAdmin)
        )
        every { userRepository.save(any()) } answers { firstArg() }
        every { refreshTokenService.issue(activeAdmin.id, activeAdmin.phone!!, "ADMIN") } returns adminTokens()

        repeat(2) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                service.loginWithGoogle("google-id-token")
            }
            assertEquals("Google 认证失败", error.message)
        }

        verify(exactly = 0) { userRepository.save(any()) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginAdmin accepts a trimmed mainland administrator phone`() {
        val admin = adminUser()
        every { userRepository.findByPhone(admin.phone!!) } returns Optional.of(admin)
        every { passwordEncoder.matches("StrongAdminPassword!1", admin.passwordHash) } returns true
        every { refreshTokenService.issue(admin.id, admin.phone!!, "ADMIN") } returns IssuedTokens(
            accessToken = "admin-access-token",
            refreshToken = "admin-refresh-token",
            accessTokenExpiresIn = 28_800
        )

        val response = service.loginAdmin("  ${admin.phone}  ", "StrongAdminPassword!1")

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
    fun `loginAdmin rejects an international administrator after a dummy password comparison`() {
        val internationalAdmin = adminUser().copy(phone = "+8613800000000")
        every { userRepository.findByPhone(internationalAdmin.phone!!) } returns Optional.of(internationalAdmin)
        every { passwordEncoder.matches("StrongAdminPassword!1", any()) } returns false

        val error = assertThrows(BadCredentialsException::class.java) {
            service.loginAdmin(internationalAdmin.phone!!, "StrongAdminPassword!1")
        }

        assertEquals("管理员账号或密码错误", error.message)
        verify(exactly = 1) { passwordEncoder.matches("StrongAdminPassword!1", any()) }
        verify(exactly = 0) { passwordEncoder.matches("StrongAdminPassword!1", internationalAdmin.passwordHash) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginAdmin rejects malformed phone after a dummy password comparison`() {
        val malformedPhone = "not-a-phone"
        val accidentalMatch = adminUser().copy(phone = malformedPhone)
        every { userRepository.findByPhone(malformedPhone) } returns Optional.of(accidentalMatch)
        every { passwordEncoder.matches("StrongAdminPassword!1", any()) } returns false

        val error = assertThrows(BadCredentialsException::class.java) {
            service.loginAdmin(malformedPhone, "StrongAdminPassword!1")
        }

        assertEquals("管理员账号或密码错误", error.message)
        verify(exactly = 1) { passwordEncoder.matches("StrongAdminPassword!1", any()) }
        verify(exactly = 0) { passwordEncoder.matches("StrongAdminPassword!1", accidentalMatch.passwordHash) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `loginAdmin rejects an unknown phone after a dummy password comparison`() {
        val unknownPhone = "13900000000"
        every { userRepository.findByPhone(unknownPhone) } returns Optional.empty()
        every { passwordEncoder.matches("StrongAdminPassword!1", any()) } returns false

        val error = assertThrows(BadCredentialsException::class.java) {
            service.loginAdmin(unknownPhone, "StrongAdminPassword!1")
        }

        assertEquals("管理员账号或密码错误", error.message)
        verify(exactly = 1) { passwordEncoder.matches("StrongAdminPassword!1", any()) }
        verify(exactly = 0) { refreshTokenService.issue(any(), any(), any()) }
    }

    @Test
    fun `logout revokes the supplied refresh token`() {
        service.logout("refresh-token")

        verify(exactly = 1) { refreshTokenService.revoke("refresh-token") }
    }

    private fun adminUser(deletedAt: LocalDateTime? = null) = UserEntity(
        id = "admin-id",
        phone = "13800000000",
        passwordHash = "admin-password-hash",
        nickname = "Admin",
        role = "ADMIN",
        deletedAt = deletedAt
    )

    private fun adminTokens() = IssuedTokens(
        accessToken = "admin-access-token",
        refreshToken = "admin-refresh-token",
        accessTokenExpiresIn = 28_800
    )

    private fun stubGoogleVerifier(verifier: GoogleIdTokenVerifier) {
        val delegate = AuthenticationService::class.java.getDeclaredField("verifier\$delegate")
        delegate.isAccessible = true
        delegate.set(service, lazyOf(verifier))
    }
}
