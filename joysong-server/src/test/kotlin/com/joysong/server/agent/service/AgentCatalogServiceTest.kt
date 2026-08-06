package com.joysong.server.agent.service

import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class AgentCatalogServiceTest {
    private val institutionRepository = mockk<InstitutionRepository>()
    private val doctorRepository = mockk<DoctorRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val discoverSearchService = mockk<DiscoverSearchService>()
    private val service = AgentCatalogService(
        institutionRepository,
        doctorRepository,
        projectRepository,
        institutionProjectRepository,
        doctorProjectRepository,
        discoverSearchService,
        mockk<DoctorInstitutionService>(),
        InstitutionProjectDetailResolver()
    )

    @Test
    fun `institution project matching uses both inherited and overridden details`() {
        val baseProject = ProjectEntity(
            id = "project-1",
            name = "基础水光",
            category = "补水护理",
            description = "基础项目介绍",
            tags = "补水,保湿"
        )
        every { projectRepository.findAll() } returns listOf(baseProject)
        every { institutionProjectRepository.findAll() } returns listOf(
            InstitutionProjectEntity(
                id = "offering-inherited",
                institutionId = "institution-1",
                projectId = baseProject.id,
                price = BigDecimal("680")
            ),
            InstitutionProjectEntity(
                id = "offering-custom",
                institutionId = "institution-2",
                projectId = baseProject.id,
                name = "院线焕亮升级版",
                description = "针对暗沉提亮肤色",
                price = BigDecimal("880")
            )
        )
        every { discoverSearchService.extractMatchingFragments("基础水光怎么样") } returns emptySet()
        every { discoverSearchService.extractMatchingFragments("院线焕亮升级版多少钱") } returns emptySet()
        every { discoverSearchService.extractMatchingFragments("我想提亮肤色") } returns setOf("提亮", "肤色")
        every { discoverSearchService.extractMatchingFragments("完全不存在的服务") } returns emptySet()

        assertTrue(service.hasInstitutionProjectMatch("基础水光怎么样"))
        assertTrue(service.hasInstitutionProjectMatch("院线焕亮升级版多少钱"))
        assertTrue(service.hasInstitutionProjectMatch("我想提亮肤色"))
        assertFalse(service.hasInstitutionProjectMatch("完全不存在的服务"))
    }
}
