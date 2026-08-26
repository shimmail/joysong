package com.joysong.server.auth.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.config.JwtTokenProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.Base64

class RefreshTokenServiceTest {

    private val tokenProvider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-characters-long",
        expiration = 60_000,
        adminExpiration = 30_000
    )

    @Test
    fun `issued access token is bound to the inserted refresh session`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        var insertedSessionId: String? = null
        every {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO refresh_tokens") }, *anyVararg())
        } answers {
            insertedSessionId = secondArg<Array<out Any>>()[0] as String
            1
        }
        val service = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)

        val issued = service.issue("admin-id", "13800000000", "ADMIN")

        assertEquals(insertedSessionId, claim(issued.accessToken, "sid"))
    }

    @Test
    fun `rotated access token is bound to the replacement refresh session`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val storedResultSet = mockk<ResultSet>()
        every { storedResultSet.getString("id") } returns "old-session-id"
        every { storedResultSet.getString("user_id") } returns "admin-id"
        every { storedResultSet.getString("role") } returns "ADMIN"
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM refresh_tokens") },
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        } answers {
            val mapper = arg<RowMapper<Any>>(1)
            listOf(mapper.mapRow(storedResultSet, 0))
        }
        every {
            jdbcTemplate.query(
                match<String> { it.contains("SELECT phone FROM users") },
                any<RowMapper<String>>(),
                *anyVararg()
            )
        } returns listOf("13800000000")
        var replacementSessionId: String? = null
        every {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO refresh_tokens") }, *anyVararg())
        } answers {
            replacementSessionId = secondArg<Array<out Any>>()[0] as String
            1
        }
        every {
            jdbcTemplate.update(match<String> { it.contains("UPDATE refresh_tokens") }, *anyVararg())
        } returns 1
        val service = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)

        val refreshed = service.rotate("a".repeat(64))

        assertEquals(replacementSessionId, claim(refreshed.tokens.accessToken, "sid"))
    }

    @Test
    fun `active session lookup binds the jwt session to its subject`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(
                match<String> {
                    it.contains("id = ?") && it.contains("user_id = ?") &&
                        it.contains("revoked_at IS NULL") && it.contains("expires_at > NOW()")
                },
                Long::class.java,
                "session-id",
                "admin-id"
            )
        } returns 1L
        every {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "session-id", "other-admin-id")
        } returns 0L
        val service = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)

        assertTrue(service.isActiveSession("session-id", "admin-id"))
        assertFalse(service.isActiveSession("session-id", "other-admin-id"))
    }

    @Test
    fun `revoke hashes the refresh token before invalidating its session`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.update(match<String> { it.contains("WHERE token_hash = ?") }, any<String>())
        } returns 1
        val service = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)
        val rawToken = "refresh-token-to-revoke"

        service.revoke(rawToken)

        io.mockk.verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("SET revoked_at") && it.contains("WHERE token_hash = ?") },
                sha256(rawToken)
            )
        }
    }

    @Test
    fun `revoked refresh token cannot be rotated`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg())
        } returns emptyList()
        val service = RefreshTokenService(jdbcTemplate, tokenProvider, 60_000)

        assertThrows(InvalidRefreshTokenException::class.java) {
            service.rotate("a".repeat(64))
        }
    }

    private fun claim(token: String, name: String): String? {
        val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1]))
        return jacksonObjectMapper().readTree(payload).path(name).textValue()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
