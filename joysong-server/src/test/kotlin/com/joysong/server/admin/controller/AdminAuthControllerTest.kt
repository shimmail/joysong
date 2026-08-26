package com.joysong.server.admin.controller

import com.joysong.server.admin.service.AdminLoginAttemptService
import com.joysong.server.auth.dto.LoginResponse
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.controller.ManagementAccessController
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify as verifyMockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional

class AdminAuthControllerTest {

    private val authenticationService = mockk<AuthenticationService>()
    private val loginAttemptService = mockk<AdminLoginAttemptService>(relaxed = true)
    private val servletRequest = mockk<HttpServletRequest>().also {
        every { it.remoteAddr } returns "127.0.0.1"
    }
    private val controller = AdminAuthController(authenticationService, loginAttemptService)

    @Test
    fun `valid administrator receives the existing login response`() {
        every { loginAttemptService.retryAfterSeconds("13800000000", "127.0.0.1") } returns null
        every { authenticationService.loginAdmin("13800000000", "StrongAdminPassword!1") } returns loginResponse()

        val response = controller.adminLogin(
            AdminLoginRequest("13800000000", "StrongAdminPassword!1"),
            servletRequest
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        assertEquals("admin-access-token", (response.body?.data as LoginResponse).accessToken)
    }

    @Test
    fun `non administrator and wrong password receive the same generic unauthorized response`() {
        every { loginAttemptService.retryAfterSeconds(any(), "127.0.0.1") } returns null
        every { authenticationService.loginAdmin("13800000001", "CorrectUserPassword!1") } throws BadCredentialsException("not admin")
        every { authenticationService.loginAdmin("13800000000", "WrongPassword!1") } throws BadCredentialsException("wrong password")

        val nonAdmin = controller.adminLogin(
            AdminLoginRequest("13800000001", "CorrectUserPassword!1"),
            servletRequest
        )
        val wrongPassword = controller.adminLogin(
            AdminLoginRequest("13800000000", "WrongPassword!1"),
            servletRequest
        )

        assertEquals(HttpStatus.UNAUTHORIZED, nonAdmin.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, wrongPassword.statusCode)
        assertEquals(nonAdmin.body?.code, wrongPassword.body?.code)
        assertEquals(nonAdmin.body?.message, wrongPassword.body?.message)
        assertEquals("管理员账号或密码错误", nonAdmin.body?.message)
    }

    @Test
    fun `rate limited login returns retry after without authenticating`() {
        every { loginAttemptService.retryAfterSeconds("13800000000", "127.0.0.1") } returns 45

        val response = controller.adminLogin(
            AdminLoginRequest("13800000000", "StrongAdminPassword!1"),
            servletRequest
        )

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.statusCode)
        assertEquals("45", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals("no-store", response.headers.getFirst(HttpHeaders.CACHE_CONTROL))
        verify(exactly = 0) { authenticationService.loginAdmin(any(), any()) }
    }

    private fun loginResponse() = LoginResponse(
        token = "admin-access-token",
        accessToken = "admin-access-token",
        refreshToken = "admin-refresh-token",
        expiresIn = 28_800,
        user = UserDto(
            id = "admin-id",
            phone = "13800000000",
            email = null,
            nickname = "Admin",
            avatar = "",
            gender = "",
            city = "",
            bio = "",
            birthday = null,
            role = "ADMIN"
        )
    )
}

@WebMvcTest(controllers = [ManagementAccessController::class])
@Import(
    SecurityConfig::class,
    JwtAuthenticationFilter::class,
    RefreshTokenService::class,
    ManagementAccessService::class
)
class AdminSessionSecurityHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
    private val refreshTokenService: RefreshTokenService
) {

    @MockBean lateinit var authenticationService: AuthenticationService
    @MockBean lateinit var userRepository: UserRepository
    @MockBean lateinit var jwtTokenProvider: JwtTokenProvider
    @MockBean lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `revoked admin session can no longer restore shared management context`() {
        var active = true
        val rawRefreshToken = "admin-refresh-token"
        given(jwtTokenProvider.validateToken("admin-access-token")).willReturn(true)
        given(jwtTokenProvider.getUserIdFromToken("admin-access-token")).willReturn("admin-id")
        given(jwtTokenProvider.getRoleFromToken("admin-access-token")).willReturn("ADMIN")
        given(jwtTokenProvider.getIssuedAtFromToken("admin-access-token")).willReturn(Date())
        given(jwtTokenProvider.getSessionIdFromToken("admin-access-token")).willReturn("session-id")
        given(userRepository.findById("admin-id")).willReturn(Optional.of(adminUser()))
        given(
            jdbcTemplate.queryForObject(
                anyString(),
                eq(Long::class.java),
                eq("session-id"),
                eq("admin-id")
            )
        ).willAnswer { if (active) 1L else 0L }
        given(jdbcTemplate.update(anyString(), anyString())).willAnswer {
            active = false
            1
        }
        given(
            jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.contains("FROM users"),
                eq(Long::class.java),
                eq("admin-id")
            )
        ).willReturn(1L)
        given(
            jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.contains("FROM user_roles"),
                eq(String::class.java),
                eq("admin-id")
            )
        ).willReturn(emptyList())

        mockMvc.perform(
            get("/api/management/context").header(HttpHeaders.AUTHORIZATION, "Bearer admin-access-token")
        ).andExpect(status().isOk)

        refreshTokenService.revoke(rawRefreshToken)

        mockMvc.perform(
            get("/api/management/context").header(HttpHeaders.AUTHORIZATION, "Bearer admin-access-token")
        ).andExpect(status().isUnauthorized)
        verifyMockito(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("WHERE token_hash = ?"),
            eq(sha256(rawRefreshToken))
        )
    }

    private fun adminUser() = UserEntity(
        id = "admin-id",
        phone = "13800000000",
        passwordHash = "hash",
        nickname = "Admin",
        role = "ADMIN",
        credentialsUpdatedAt = LocalDateTime.now().minusMinutes(1)
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
