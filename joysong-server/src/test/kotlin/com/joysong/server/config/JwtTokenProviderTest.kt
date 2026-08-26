package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val provider = JwtTokenProvider(
        secret = "test-secret-key-that-is-at-least-32-characters-long",
        expiration = 60_000,
        adminExpiration = 30_000
    )

    @Test
    fun `管理员令牌包含受控角色并可通过签名校验`() {
        val token = provider.generateToken("admin-id", "13800000000", "ADMIN")

        assertTrue(provider.validateToken(token))
        assertEquals("admin-id", provider.getUserIdFromToken(token))
        assertEquals("ADMIN", provider.getRoleFromToken(token))
    }

    @Test
    fun `未知角色不会被提升为管理员`() {
        val token = provider.generateToken("user-id", "13800138001", "SUPER_ADMIN")

        assertTrue(provider.validateToken(token))
        assertEquals("USER", provider.getRoleFromToken(token))
    }

    @Test
    fun `legacy token has no bound refresh session`() {
        val token = provider.generateToken("admin-id", "13800000000", "ADMIN")
        val payload = String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1]))
        val sid = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readTree(payload)
            .path("sid")
            .textValue()

        assertNull(sid)
    }

    @Test
    fun `access token exposes its bound refresh session`() {
        val token = provider.generateToken(
            userId = "admin-id",
            phone = "13800000000",
            role = "ADMIN",
            sessionId = "refresh-session-id"
        )

        assertEquals("refresh-session-id", provider.getSessionIdFromToken(token))
    }

    @Test
    fun `user token does not expose an administrator session identifier`() {
        val token = provider.generateToken(
            userId = "doctor-user-id",
            phone = "13800000001",
            role = "USER",
            sessionId = "refresh-session-id"
        )

        assertNull(provider.getSessionIdFromToken(token))
    }
}
