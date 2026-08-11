package com.joysong.server.home.service

import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.banner.repository.BannerRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HomeServiceTest {
    private val articleRepository = mockk<ArticleRepository>()
    private val diaryRepository = mockk<DiaryRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val institutionRepository = mockk<InstitutionRepository>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val service = HomeService(
        bannerRepository = mockk<BannerRepository>(),
        projectRepository = projectRepository,
        articleRepository = articleRepository,
        diaryRepository = diaryRepository,
        institutionProjectRepository = institutionProjectRepository,
        institutionRepository = institutionRepository,
        institutionProjectDetailResolver = InstitutionProjectDetailResolver(),
        doctorProjectRepository = doctorProjectRepository
    )

    @Test
    fun `recommended projects use database limited query and batch load relations`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-1", institutionId = "institution-1", projectId = "project-1",
            price = BigDecimal("1000"), salesCount = 9
        )
        every { institutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc() } returns listOf(institutionProject)
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(ProjectEntity("project-1", "Project"))
        every { institutionRepository.findAllById(listOf("institution-1")) } returns listOf(InstitutionEntity("institution-1", "Institution"))
        every { doctorProjectRepository.findActiveByInstitutionProjectId("ip-1") } returns listOf(
            DoctorProjectEntity(
                doctorId = "doctor-1",
                institutionProjectId = "ip-1",
                price = BigDecimal("800")
            )
        )

        val result = service.getRecommendedInstitutionProjects()

        assertEquals(1, result.size)
        assertEquals("ip-1", result.single().institutionProjectId)
        verify(exactly = 1) { institutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc() }
        verify(exactly = 0) { institutionProjectRepository.findAll() }
        verify(exactly = 1) { projectRepository.findAllById(listOf("project-1")) }
        verify(exactly = 1) { institutionRepository.findAllById(listOf("institution-1")) }
    }

    @Test
    fun `recommended projects use minimum doctor price and exclude projects without doctor bindings`() {
        val pricedProject = InstitutionProjectEntity(
            id = "ip-priced",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000")
        )
        val unboundProject = InstitutionProjectEntity(
            id = "ip-unbound",
            institutionId = "institution-1",
            projectId = "project-2",
            price = BigDecimal("100")
        )
        every { institutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc() } returns
            listOf(pricedProject, unboundProject)
        every { projectRepository.findAllById(listOf("project-1", "project-2")) } returns listOf(
            ProjectEntity("project-1", "Priced Project"),
            ProjectEntity("project-2", "Unbound Project")
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns
            listOf(InstitutionEntity("institution-1", "Institution"))
        every { doctorProjectRepository.findActiveByInstitutionProjectId("ip-priced") } returns listOf(
            DoctorProjectEntity(
                doctorId = "doctor-expensive",
                institutionProjectId = "ip-priced",
                price = BigDecimal("900")
            ),
            DoctorProjectEntity(
                doctorId = "doctor-affordable",
                institutionProjectId = "ip-priced",
                price = BigDecimal("700")
            )
        )
        every { doctorProjectRepository.findActiveByInstitutionProjectId("ip-unbound") } returns emptyList()

        val result = service.getRecommendedInstitutionProjects()

        assertEquals(listOf("ip-priced"), result.map { it.institutionProjectId })
        assertEquals(BigDecimal("700"), result.single().price)
    }

    @Test
    fun `home sections use bounded repository queries`() {
        every { projectRepository.findTop8ByOrderBySalesCountDesc() } returns emptyList()
        every { articleRepository.findTop6ByOrderByPublishDateDesc() } returns emptyList()
        every { diaryRepository.findTop8ByStatusOrderByPublishDateDesc("published") } returns emptyList()

        service.getHotProjects()
        service.getExpertArticles()
        service.getUserDiaries()

        verify(exactly = 1) { projectRepository.findTop8ByOrderBySalesCountDesc() }
        verify(exactly = 1) { articleRepository.findTop6ByOrderByPublishDateDesc() }
        verify(exactly = 1) { diaryRepository.findTop8ByStatusOrderByPublishDateDesc("published") }
        verify(exactly = 0) { projectRepository.findAll() }
        verify(exactly = 0) { articleRepository.findAll() }
        verify(exactly = 0) { diaryRepository.findPublishedOrderByPublishDateDesc() }
    }
}
