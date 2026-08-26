package com.joysong.server.config

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional

class JwtAuthenticationFilterTest {

    private val secret = "test-secret-key-that-is-at-least-32-characters-long"
    private val tokenProvider = JwtTokenProvider(secret, 60_000, 30_000)
    private val userRepository = mockk<UserRepository>()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `admin token without a bound session does not authenticate`() {
        val token = token(role = "ADMIN", sessionId = null)
        every { userRepository.findById("admin-id") } returns Optional.of(user("admin-id", "ADMIN"))

        val jdbcTemplate = authenticate(token, activeSession = true)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 0) {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg())
        }
    }

    @Test
    fun `revoked admin session does not authenticate`() {
        val token = token(role = "ADMIN", sessionId = "revoked-session")
        every { userRepository.findById("admin-id") } returns Optional.of(user("admin-id", "ADMIN"))

        val jdbcTemplate = authenticate(token, activeSession = false)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 1) {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "revoked-session", "admin-id")
        }
    }

    @Test
    fun `active admin session authenticates with admin authority`() {
        val token = token(role = "ADMIN", sessionId = "active-session")
        every { userRepository.findById("admin-id") } returns Optional.of(user("admin-id", "ADMIN"))

        val jdbcTemplate = authenticate(token, activeSession = true)

        assertEquals("ROLE_ADMIN", SecurityContextHolder.getContext().authentication.authorities.single().authority)
        verify(exactly = 1) {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "active-session", "admin-id")
        }
    }

    @Test
    fun `user token remains valid without an admin session lookup`() {
        val token = token(userId = "doctor-user", role = "USER", sessionId = null)
        every { userRepository.findById("doctor-user") } returns Optional.of(user("doctor-user", "USER"))

        val jdbcTemplate = authenticate(token, activeSession = false)

        assertEquals("ROLE_USER", SecurityContextHolder.getContext().authentication.authorities.single().authority)
        verify(exactly = 0) {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg())
        }
    }

    @Test
    fun `session owned by another user does not authenticate current admin`() {
        val token = token(role = "ADMIN", sessionId = "shared-session")
        every { userRepository.findById("admin-id") } returns Optional.of(user("admin-id", "ADMIN"))

        val jdbcTemplate = authenticate(token, activeSession = false)

        assertNull(SecurityContextHolder.getContext().authentication)
        verify(exactly = 1) {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "shared-session", "admin-id")
        }
    }

    private fun authenticate(token: String, activeSession: Boolean): JdbcTemplate {
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer $token") }
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("refresh_tokens") },
                Long::class.java,
                *anyVararg()
            )
        } returns if (activeSession) 1L else 0L
        val refreshTokenService = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)
        val filter = JwtAuthenticationFilter(tokenProvider, userRepository, providerOf(refreshTokenService))

        filter.doFilter(request, MockHttpServletResponse(), mockk<FilterChain>(relaxed = true))
        return jdbcTemplate
    }

    private fun providerOf(service: RefreshTokenService): ObjectProvider<RefreshTokenService> {
        val beans = StaticListableBeanFactory()
        beans.addBean("refreshTokenService", service)
        return beans.getBeanProvider(RefreshTokenService::class.java)
    }

    private fun token(
        userId: String = "admin-id",
        role: String,
        sessionId: String?
    ): String {
        val builder = Jwts.builder()
            .issuer("joysong-server")
            .subject(userId)
            .claim("phone", "13800000000")
            .claim("role", role)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
        if (sessionId != null) builder.claim("sid", sessionId)
        return builder.signWith(Keys.hmacShaKeyFor(secret.toByteArray())).compact()
    }

    private fun user(id: String, role: String) = UserEntity(
        id = id,
        phone = "13800000000",
        passwordHash = "hash",
        nickname = "User",
        role = role,
        credentialsUpdatedAt = LocalDateTime.now().minusMinutes(1)
    )
}
