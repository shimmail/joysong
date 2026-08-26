package com.joysong.server.config

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:}") private val secret: String,
    @Value("\${jwt.expiration}") private val expiration: Long,
    @Value("\${jwt.admin-expiration:28800000}") private val adminExpiration: Long
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generateToken(
        userId: String,
        phone: String,
        role: String = "USER",
        sessionId: String? = null
    ): String {
        val normalizedRole = role.uppercase().takeIf { it == "ADMIN" } ?: "USER"
        val tokenExpiration = if (normalizedRole == "ADMIN") adminExpiration else expiration
        val builder = Jwts.builder()
            .issuer("joysong-server")
            .subject(userId)
            .claim("phone", phone)
            .claim("role", normalizedRole)
            .claim("tokenType", if (normalizedRole == "ADMIN") "ADMIN_ACCESS" else "USER_ACCESS")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + tokenExpiration))
        if (normalizedRole == "ADMIN" && !sessionId.isNullOrBlank()) {
            builder.claim("sid", sessionId)
        }
        return builder
            .signWith(key)
            .compact()
    }

    fun expirationSeconds(role: String): Long {
        val normalizedRole = role.uppercase().takeIf { it == "ADMIN" } ?: "USER"
        return (if (normalizedRole == "ADMIN") adminExpiration else expiration) / 1000
    }

    fun validateToken(token: String): Boolean {
        return try {
            val claims = getClaims(token)
            !claims.expiration.before(Date())
        } catch (e: Exception) {
            false
        }
    }

    fun getUserIdFromToken(token: String): String = getClaims(token).subject

    fun getIssuedAtFromToken(token: String): Date = getClaims(token).issuedAt

    fun getSessionIdFromToken(token: String): String? =
        (getClaims(token)["sid"] as? String)?.takeIf { it.isNotBlank() }

    fun getRoleFromToken(token: String): String {
        return (getClaims(token)["role"] as? String)
            ?.uppercase()
            ?.takeIf { it == "USER" || it == "ADMIN" }
            ?: "USER"
    }

    private fun getClaims(token: String): Claims {
        return Jwts.parser()
            .verifyWith(key)
            .requireIssuer("joysong-server")
            .build()
            .parseSignedClaims(token)
            .payload
    }
}
