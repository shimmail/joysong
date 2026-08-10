package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.entity.AgentAssessmentEntity
import com.joysong.server.agent.entity.AgentPlanEntity
import com.joysong.server.agent.entity.AgentPlanItemEntity
import com.joysong.server.agent.entity.AgentUserProfileEntity
import com.joysong.server.agent.repository.AgentPlanItemRepository
import com.joysong.server.agent.repository.AgentPlanRepository
import com.joysong.server.agent.repository.AgentUserProfileRepository
import com.joysong.server.institution.entity.InstitutionProjectEntity
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
    fun `excluded project names ignore surrounding whitespace and case`() {
        every { profileRepository.findByUserId("user-1") } returns profile(
            excludedProjectsJson = """["  laser peel  "]"""
        )
        every { projectRepository.findAll() } returns listOf(project("project-1", "LASER PEEL"))

        val result = service.create("user-1", "assessment-1")

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `institution override names are hard exclusions`() {
        every { profileRepository.findByUserId("user-1") } returns profile(
            excludedProjectsJson = """["院线焕肤升级版"]"""
        )
        every { projectRepository.findAll() } returns listOf(project("project-1", "基础焕肤"))
        every { institutionProjectRepository.findAll() } returns listOf(
            InstitutionProjectEntity(
                id = "offering-1",
                institutionId = "institution-1",
                projectId = "project-1",
                name = "院线焕肤升级版",
                price = BigDecimal("2000")
            )
        )

        val result = service.create("user-1", "assessment-1")

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `description slogan and detail only matches do not become candidates`() {
        every { assessmentService.getEntity("assessment-1", "user-1") } returns readyAssessment("神奇词")
        every { profileRepository.findByUserId("user-1") } returns profile()
        every { projectRepository.findAll() } returns listOf(
            project(
                id = "project-1",
                name = "普通项目",
                category = "其他",
                tags = "基础",
                description = "神奇词恢复期1天",
                slogan = "神奇词无痛",
                detailContent = "<p>神奇词零风险</p>"
            )
        )

        val result = service.create("user-1", "assessment-1")

        assertTrue(result.items.isEmpty())
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

    @Test
    fun `legacy plans are downgraded to a safe read only projection`() {
        every { planRepository.findByIdAndUserId("plan-1", "user-1") } returns AgentPlanEntity(
            id = "plan-1",
            userId = "user-1",
            assessmentId = "assessment-1",
            version = 1,
            status = "READY",
            summary = "为你制定的最适合方案，已结合恢复期和疼痛偏好"
        )
        every { itemRepository.findByPlanIdOrderBySortOrderAsc("plan-1") } returns listOf(
            AgentPlanItemEntity(
                id = "item-1",
                planId = "plan-1",
                stageName = "首选治疗",
                projectId = "project-1",
                projectName = "旧项目",
                recommendationType = "CONSIDER",
                reason = "这是最适合你的个性化治疗方案",
                expectedBenefit = "恢复期1天、无痛、零风险",
                limitations = "旧限制",
                risksJson = "[]",
                alternativesJson = "[]",
                requiredConfirmationJson = "[]",
                confidence = "MEDIUM",
                sortOrder = 0
            )
        )

        val result = service.get("user-1", "plan-1")

        assertTrue(result.summary.contains("信息参考"))
        assertTrue(result.summary.contains("不构成诊断或治疗建议"))
        val item = result.items.single()
        assertEquals("信息参考", item.stage)
        assertEquals("REFERENCE", item.recommendationType)
        assertEquals("LOW", item.confidence)
        assertFalse(item.reason.contains("关键词关联"))
        assertFalse(item.reason.contains("已确认目标"))
        assertTrue(item.reason.contains("不代表目标匹配"))
        val visibleText = listOf(result.summary, item.stage, item.reason, item.expectedBenefit).joinToString(" ")
        assertFalse(visibleText.contains("最适合"))
        assertFalse(visibleText.contains("为你制定"))
        assertFalse(visibleText.contains("治疗方案"))
        assertFalse(visibleText.contains("恢复期1天"))
        assertFalse(visibleText.contains("无痛"))
        assertFalse(visibleText.contains("零风险"))
    }

    private fun readyAssessment(goal: String = "肤质") = AgentAssessmentEntity(
        id = "assessment-1",
        userId = "user-1",
        status = "READY_FOR_PLANNING",
        completenessScore = 100,
        goalSnapshotJson = objectMapper.writeValueAsString(listOf(goal)),
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

    private fun project(
        id: String,
        name: String,
        category: String = "肤质管理",
        tags: String = "肤质",
        description: String = "平台项目介绍",
        slogan: String = "",
        detailContent: String? = null
    ) = ProjectEntity(
        id = id,
        name = name,
        category = category,
        description = description,
        tags = tags,
        slogan = slogan,
        detailContent = detailContent,
        referencePrice = BigDecimal("2000")
    )
}
