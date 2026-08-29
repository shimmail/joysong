package com.joysong.server.admin.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.diary.service.DiaryService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.service.UserProfileService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime

class AdminUserControllerTest {
    private val userProfileService = mockk<UserProfileService>()
    private val objectMapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AdminUserController(userProfileService, mockk<DiaryService>()))
        .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
        .build()

    @Test
    fun `list response is whitelisted and keeps all account states visible`() {
        val activeUser = user(id = "active-user")
        val erasedUser = user(id = "erased-user", accountState = AccountState.ERASED)
        every { userProfileService.adminListUsers(null) } returns listOf(activeUser, erasedUser)

        val data = performAndReadData(get("/api/admin/users"))

        assertEquals(2, data.size())
        assertAdminUserJson(data[0], "active-user", "USER")
        assertAdminUserJson(data[1], "erased-user", "USER")
        assertEquals("ERASED", data[1].path("accountState").asText())
    }

    @Test
    fun `detail response is whitelisted`() {
        every { userProfileService.adminFindById("user-1") } returns user()

        val data = performAndReadData(get("/api/admin/users/user-1"))

        assertAdminUserJson(data, "user-1", "USER")
        assertEquals("admin@example.com", data.path("email").asText())
        assertEquals("https://example.com/avatar.png", data.path("avatar").asText())
        assertEquals("1990-01-02", data.path("birthday").asText())
    }

    @Test
    fun `role update endpoint is unavailable`() {
        mockMvc.perform(
            put("/api/admin/users/user-1/role")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"role":"ADMIN"}""")
        )

            .andExpect(status().isNotFound)
    }

    @Test
    fun `reactivate response is whitelisted`() {
        every { userProfileService.adminReactivate("user-1") } returns user(accountState = AccountState.ACTIVE)

        val data = performAndReadData(put("/api/admin/users/user-1/reactivate"))

        assertAdminUserJson(data, "user-1", "USER")
    }

    @Test
    fun `list response exposes account lifecycle state`() {
        every { userProfileService.adminListUsers(null) } returns listOf(
            user(id = "suspended", accountState = AccountState.ADMIN_SUSPENDED),
        )

        val data = performAndReadData(get("/api/admin/users"))

        assertEquals("ADMIN_SUSPENDED", data[0].path("accountState").asText())
    }

    private fun performAndReadData(request: MockHttpServletRequestBuilder): JsonNode {
        val responseBody = mockMvc.perform(request)
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(StandardCharsets.UTF_8)

        assertFalse(responseBody.contains(TEST_PASSWORD_HASH))
        return objectMapper.readTree(responseBody).path("data")
    }

    private fun assertAdminUserJson(data: JsonNode, expectedId: String, expectedRole: String) {
        assertEquals(WHITELISTED_FIELDS, data.fieldNames().asSequence().toSet())
        assertEquals(expectedId, data.path("id").asText())
        assertEquals("+8613800000000", data.path("phone").asText())
        assertEquals("管理员", data.path("nickname").asText())
        assertEquals("上海", data.path("city").asText())
        assertEquals("bio", data.path("bio").asText())
        assertEquals(expectedRole, data.path("role").asText())
        assertEquals("2026-08-01T10:15:30", data.path("createdAt").asText())
        assertEquals("2026-08-02T11:16:31", data.path("updatedAt").asText())
        assertTrue(data.path("hasPassword").asBoolean())
        assertFalse(data.has("passwordHash"))
        assertFalse(data.has("credentialsUpdatedAt"))
    }

    private fun user(
        id: String = "user-1",
        role: String = "USER",
        accountState: AccountState = AccountState.ACTIVE,
    ) = UserEntity(
        id = id,
        phone = "+8613800000000",
        email = "admin@example.com",
        passwordHash = TEST_PASSWORD_HASH,
        nickname = "管理员",
        avatar = "https://example.com/avatar.png",
        gender = "OTHER",
        city = "上海",
        bio = "bio",
        birthday = LocalDate.parse("1990-01-02"),
        role = role,
        createdAt = LocalDateTime.parse("2026-08-01T10:15:30"),
        updatedAt = LocalDateTime.parse("2026-08-02T11:16:31"),
        credentialsUpdatedAt = LocalDateTime.parse("2026-08-03T12:17:32"),
        accountState = accountState,
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
            "accountState",
            "createdAt",
            "updatedAt",
            "hasPassword"
        )
    }
}
