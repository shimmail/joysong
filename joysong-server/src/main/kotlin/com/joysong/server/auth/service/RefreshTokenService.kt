package com.joysong.server.auth.service

import com.joysong.server.config.JwtTokenProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

class InvalidRefreshTokenException(message: String = "刷新令牌无效或已过期") : RuntimeException(message)

data class IssuedTokens(
    val accessToken: String,
    val refreshToken: String,
    val accessTokenExpiresIn: Long
)

data class RefreshedSession(
    val userId: String,
    val tokens: IssuedTokens
)

private data class StoredRefreshToken(
    val id: String,
    val userId: String,
    val role: String
)

private data class NewRefreshToken(
    val id: String,
    val rawToken: String
)

@Service
class RefreshTokenService(
    private val jdbcTemplate: JdbcTemplate,
    private val jwtTokenProvider: JwtTokenProvider,
    @Value("\${jwt.refresh-expiration:2592000000}") private val refreshExpiration: Long
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun issue(userId: String, phone: String, role: String): IssuedTokens {
        val refresh = insertRefreshToken(userId)
        return IssuedTokens(
            accessToken = jwtTokenProvider.generateToken(userId, phone, role),
            refreshToken = refresh.rawToken,
            accessTokenExpiresIn = jwtTokenProvider.expirationSeconds(role)
        )
    }

    @Transactional
    fun rotate(rawToken: String): RefreshedSession {
        val normalized = rawToken.trim()
        if (normalized.length !in 32..512) throw InvalidRefreshTokenException()
        val stored = jdbcTemplate.query(
            """
            SELECT rt.id, rt.user_id, u.role
            FROM refresh_tokens rt
            JOIN users u ON u.id = rt.user_id
            WHERE rt.token_hash = ?
              AND rt.revoked_at IS NULL
              AND rt.expires_at > NOW()
              AND u.deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> StoredRefreshToken(rs.getString("id"), rs.getString("user_id"), rs.getString("role")) },
            hash(normalized)
        ).firstOrNull() ?: throw InvalidRefreshTokenException()

        val user = jdbcTemplate.query(
            "SELECT phone FROM users WHERE id = ? AND deleted_at IS NULL",
            { rs, _ -> rs.getString("phone") ?: "" },
            stored.userId
        ).firstOrNull() ?: throw InvalidRefreshTokenException()
        val replacement = insertRefreshToken(stored.userId)
        val updated = jdbcTemplate.update(
            """
            UPDATE refresh_tokens
            SET revoked_at = NOW(), last_used_at = NOW(), replaced_by_token_id = ?
            WHERE id = ? AND revoked_at IS NULL
            """.trimIndent(),
            replacement.id,
            stored.id
        )
        if (updated != 1) throw InvalidRefreshTokenException()

        return RefreshedSession(
            userId = stored.userId,
            tokens = IssuedTokens(
                accessToken = jwtTokenProvider.generateToken(stored.userId, user, stored.role),
                refreshToken = replacement.rawToken,
                accessTokenExpiresIn = jwtTokenProvider.expirationSeconds(stored.role)
            )
        )
    }

    fun revoke(rawToken: String) {
        val normalized = rawToken.trim()
        if (normalized.isBlank()) return
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE token_hash = ?",
            hash(normalized)
        )
    }

    fun revokeAll(userId: String) {
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = ?",
            userId
        )
    }

    private fun insertRefreshToken(userId: String): NewRefreshToken {
        val id = UUID.randomUUID().toString()
        val bytes = ByteArray(48).also(secureRandom::nextBytes)
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        require(refreshExpiration > 0) { "jwt.refresh-expiration 必须大于 0" }
        val expiresAt = LocalDateTime.now().plus(Duration.ofMillis(refreshExpiration))
        jdbcTemplate.update(
            "INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
            id,
            userId,
            hash(rawToken),
            expiresAt
        )
        return NewRefreshToken(id, rawToken)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
