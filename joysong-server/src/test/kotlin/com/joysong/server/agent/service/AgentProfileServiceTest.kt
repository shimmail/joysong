package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentUserProfileEntity
import com.joysong.server.agent.repository.AgentUserProfileRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import java.math.BigDecimal
import java.util.Locale

class AgentProfileServiceTest {
    private val repository = mockk<AgentUserProfileRepository>()
    private val service = AgentProfileService(repository, ObjectMapper())

    @BeforeEach
    fun useChineseLocale() {
        LocaleContextHolder.setLocale(Locale.CHINESE)
    }

    @Test
    fun `reports every required field that is missing`() {
        val profile = AgentUserProfileEntity(id = "profile-1", userId = "user-1")

        assertEquals(
            listOf("改善目标", "所在城市", "预算上限", "可接受恢复期", "疼痛接受度"),
            service.missingFields(profile)
        )
        assertEquals(0, service.completeness(profile))
    }

    @Test
    fun `complete profile reaches one hundred percent`() {
        val profile = AgentUserProfileEntity(
            id = "profile-1",
            userId = "user-1",
            city = "上海",
            goalsJson = "[\"肤质\"]",
            budgetMax = BigDecimal("10000"),
            acceptableDowntimeDays = 3,
            painTolerance = "LOW"
        )

        assertEquals(emptyList<String>(), service.missingFields(profile))
        assertEquals(100, service.completeness(profile))
    }
}
