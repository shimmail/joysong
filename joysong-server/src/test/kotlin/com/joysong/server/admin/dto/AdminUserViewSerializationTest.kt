package com.joysong.server.admin.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.user.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AdminUserViewSerializationTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `admin user view serializes only whitelisted fields`() {
        val json = objectMapper.readTree(objectMapper.writeValueAsString(user().toAdminUserView()))

        assertEquals(WHITELISTED_FIELDS, json.fieldNames().asSequence().toSet())
        assertEquals("user-1", json.path("id").asText())
        assertEquals("admin@example.com", json.path("email").asText())
        assertEquals("ADMIN", json.path("role").asText())
        assertTrue(json.path("hasPassword").asBoolean())
        assertFalse(json.toString().contains(TEST_PASSWORD_HASH))
    }

    @Test
    fun `admin user view reports when no password is configured`() {
        val json = objectMapper.readTree(
            objectMapper.writeValueAsString(user(passwordHash = "").toAdminUserView())
        )

        assertFalse(json.path("hasPassword").asBoolean())
    }

    @Test
    fun `user entity serialization defensively omits password hash`() {
        val json = objectMapper.writeValueAsString(user())

        assertFalse(json.contains("passwordHash"))
        assertFalse(json.contains(TEST_PASSWORD_HASH))
    }

    private fun user(passwordHash: String = TEST_PASSWORD_HASH) = UserEntity(
        id = "user-1",
        phone = "+8613800000000",
        email = "admin@example.com",
        passwordHash = passwordHash,
        nickname = "管理员",
        avatar = "https://example.com/avatar.png",
        gender = "OTHER",
        city = "上海",
        bio = "bio",
        birthday = LocalDate.parse("1990-01-02"),
        role = "ADMIN",
        createdAt = LocalDateTime.parse("2026-08-01T10:15:30"),
        updatedAt = LocalDateTime.parse("2026-08-02T11:16:31"),
        credentialsUpdatedAt = LocalDateTime.parse("2026-08-03T12:17:32"),
        deletedAt = LocalDateTime.parse("2026-08-04T13:18:33")
    )

    private companion object {
        const val TEST_PASSWORD_HASH = "test-only-password-hash-4f79c6"

        val WHITELISTED_FIELDS = setOf(
            "id",
            "phone",
            "email",
            "nickname",
            "avatar",
            "gender",
            "city",
            "bio",
            "birthday",
            "role",
            "createdAt",
            "updatedAt",
            "deletedAt",
            "hasPassword"
        )
    }
}
