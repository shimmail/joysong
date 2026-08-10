package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentAssessmentEntity
import com.joysong.server.agent.entity.AgentPlanEntity
import com.joysong.server.agent.entity.AgentPlanItemEntity
import com.joysong.server.agent.entity.AgentUserProfileEntity
import com.joysong.server.agent.repository.AgentPlanItemRepository
import com.joysong.server.agent.repository.AgentPlanRepository
import com.joysong.server.agent.repository.AgentUserProfileRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import java.math.BigDecimal
import java.util.Locale

class AgentPlanServiceTest {
    private val planRepository = mockk<AgentPlanRepository>()
    private val itemRepository = mockk<AgentPlanItemRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val profileRepository = mockk<AgentUserProfileRepository>()
    private val assessmentService = mockk<AgentAssessmentService>()
    private val objectMapper = ObjectMapper()
    private val profileService = AgentProfileService(profileRepository, objectMapper)
    private val service = AgentPlanService(
        planRepository,
        itemRepository,
        projectRepository,
        institutionProjectRepository,
        InstitutionProjectDetailResolver(),
        profileService,
        assessmentService,
        objectMapper
    )

    @BeforeEach
    fun setUp() {
        LocaleContextHolder.setLocale(Locale.CHINESE)
        every { assessmentService.getEntity("assessment-1", "user-1") } returns readyAssessment()
        every { planRepository.countByUserId("user-1") } returns 0
        every { planRepository.save(any()) } answers { firstArg<AgentPlanEntity>() }
        every { itemRepository.save(any()) } answers { firstArg<AgentPlanItemEntity>() }
        every { institutionProjectRepository.findAll() } returns emptyList()
    }

    @Test
    fun `excluded project ids are never presented as candidates`() {
        every { profileRepository.findByUserId("user-1") } returns profile(
            excludedProjectsJson = """["excluded-project"]"""
        )
        every { projectRepository.findAll() } returns listOf(
            project("excluded-project", "光电项目 A"),
            project("included-project", "光电项目 B")
        )

        val result = service.create("user-1", "assessment-1")

        assertEquals(listOf("included-project"), result.items.map { it.projectId })
    }

    @Test
    fun `plan is an information reference and unknown medical data is not invented from free text`() {
        every { profileRepository.findByUserId("user-1") } returns profile()
        every { projectRepository.findAll() } returns listOf(
            project(
                id = "project-1",
                name = "焕肤项目",
                description = "最适合你，恢复期1天、无痛、零风险"
            )
        )

        val result = service.create("user-1", "assessment-1")

        assertTrue(result.summary.contains("信息参考"))
        assertTrue(result.summary.contains("不构成诊断或治疗建议"))
        assertTrue(result.summary.contains("可接受恢复期偏好：3天"))
        assertTrue(result.summary.contains("疼痛接受度偏好：LOW"))
        assertFalse(result.summary.contains("为你制定"))
        assertFalse(result.summary.contains("最适合"))
        assertFalse(result.summary.contains("已结合恢复期"))
        assertFalse(result.summary.contains("已结合疼痛"))

        val item = result.items.single()
        val userFacingItemText = listOf(
            item.reason,
            item.expectedBenefit,
            item.limitations,
            *item.risks.toTypedArray(),
            *item.requiredConfirmations.toTypedArray()
        ).joinToString(" ")
        assertFalse(userFacingItemText.contains("恢复期1天"))
        assertFalse(userFacingItemText.contains("无痛"))
        assertFalse(userFacingItemText.contains("零风险"))
        assertFalse(userFacingItemText.contains("最适合"))
        assertTrue(item.risks.contains("风险信息：需向机构确认"))
        assertTrue(item.requiredConfirmations.contains("恢复期：需向机构确认"))
        assertTrue(item.requiredConfirmations.contains("疼痛程度：需向机构确认"))
        assertTrue(item.requiredConfirmations.contains("禁忌与风险：需向机构确认"))
    }

    private fun readyAssessment() = AgentAssessmentEntity(
        id = "assessment-1",
        userId = "user-1",
        status = "READY_FOR_PLANNING",
        completenessScore = 100,
        goalSnapshotJson = """["肤质"]""",
        riskLevel = "NONE",
        riskReasonsJson = "[]",
        missingFieldsJson = "[]"
    )

    private fun profile(excludedProjectsJson: String = "[]") = AgentUserProfileEntity(
        id = "profile-1",
        userId = "user-1",
        city = "上海",
        goalsJson = """["肤质"]""",
        budgetMin = BigDecimal("1000"),
        budgetMax = BigDecimal("5000"),
        acceptableDowntimeDays = 3,
        painTolerance = "LOW",
        excludedProjectsJson = excludedProjectsJson,
        consentVersion = "v1"
    )

    private fun project(id: String, name: String, description: String = "平台项目介绍") = ProjectEntity(
        id = id,
        name = name,
        category = "肤质管理",
        description = description,
        tags = "肤质",
        referencePrice = BigDecimal("2000")
    )
}
