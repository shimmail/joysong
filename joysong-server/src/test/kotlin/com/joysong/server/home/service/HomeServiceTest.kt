package com.joysong.server.home.service

import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.banner.repository.BannerRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.repository.PublicDoctorProjectView
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

    private fun publicBinding(
        doctorId: String,
        institutionProjectId: String,
        projectId: String,
        price: String
    ): PublicDoctorProjectView = mockk<PublicDoctorProjectView>().also { binding ->
        every { binding.doctorId } returns doctorId
        every { binding.projectId } returns projectId
        every { binding.institutionProjectId } returns institutionProjectId
        every { binding.price } returns BigDecimal(price)
    }

    @Test
    fun `recommended projects filter unavailable high sales entries before top eight limit`() {
        val unavailable = (1..8).map { index ->
            InstitutionProjectEntity(
                id = "ip-unavailable-$index",
                institutionId = "institution-1",
                projectId = "project-unavailable-$index",
                price = BigDecimal("100"),
                salesCount = 100 - index
            )
        }
        val available = InstitutionProjectEntity(
            id = "ip-available",
            institutionId = "institution-1",
            projectId = "project-available",
            price = BigDecimal("1000"),
            salesCount = 1
        )
        val ordered = unavailable + available
        every { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() } returns ordered
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(ordered.map { it.id }) } returns listOf(
            publicBinding("doctor-1", "ip-available", "project-available", "700")
        )
        every { projectRepository.findAllById(listOf("project-available")) } returns listOf(
            ProjectEntity("project-available", "Available")
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns listOf(
            InstitutionEntity("institution-1", "Institution")
        )

        val result = service.getRecommendedInstitutionProjects()

        assertEquals(listOf("ip-available"), result.map { it.institutionProjectId })
        assertEquals(BigDecimal("700"), result.single().price)
        verify(exactly = 0) { institutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc() }
    }

    @Test
    fun `recommended projects use sales ordered query and batch load relations`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-1", institutionId = "institution-1", projectId = "project-1",
            price = BigDecimal("1000"), salesCount = 9, caseCount = 29
        )
        every { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() } returns listOf(institutionProject)
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(
            ProjectEntity("project-1", "Project", caseCount = 92)
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns listOf(InstitutionEntity("institution-1", "Institution"))
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-1")) } returns listOf(
            publicBinding("doctor-1", "ip-1", "project-1", "800")
        )

        val result = service.getRecommendedInstitutionProjects()

        assertEquals(1, result.size)
        assertEquals("ip-1", result.single().institutionProjectId)
        assertEquals(29, result.single().caseCount)
        verify(exactly = 1) { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() }
        verify(exactly = 0) { institutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc() }
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
        every { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() } returns
            listOf(pricedProject, unboundProject)
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(
            ProjectEntity("project-1", "Priced Project")
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns
            listOf(InstitutionEntity("institution-1", "Institution"))
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-priced", "ip-unbound"))
        } returns listOf(
            publicBinding("doctor-expensive", "ip-priced", "project-1", "900"),
            publicBinding("doctor-affordable", "ip-priced", "project-1", "700")
        )

        val result = service.getRecommendedInstitutionProjects()

        assertEquals(listOf("ip-priced"), result.map { it.institutionProjectId })
        assertEquals(BigDecimal("700"), result.single().price)
    }

    @Test
    fun `hot projects filter unavailable entries before top eight and use minimum eligible doctor price`() {
        val unavailableProjects = (1..8).map { index ->
            ProjectEntity(
                id = "project-unavailable-$index",
                name = "Unavailable $index",
                referencePrice = BigDecimal("100"),
                salesCount = 100 - index
            )
        }
        val availableProject = ProjectEntity(
            id = "project-available",
            name = "Available",
            referencePrice = BigDecimal("1200"),
            salesCount = 1
        )
        val institutionProjects = (unavailableProjects + availableProject).map { project ->
            InstitutionProjectEntity(
                id = "ip-${project.id}",
                institutionId = "institution-1",
                projectId = project.id,
                price = project.referencePrice,
                salesCount = project.salesCount
            )
        }
        every { projectRepository.findTop8ByOrderBySalesCountDesc() } returns unavailableProjects
        every { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() } returns institutionProjects
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(institutionProjects.map { it.id })
        } returns listOf(
            publicBinding("doctor-expensive", "ip-project-available", "project-available", "800"),
            publicBinding("doctor-affordable", "ip-project-available", "project-available", "650")
        )
        every { projectRepository.findAll() } returns unavailableProjects + availableProject

        val result = service.getHotProjects()

        assertEquals(listOf("project-available"), result.map { it.id })
        assertEquals(BigDecimal("650"), result.single().referencePrice)
    }

    @Test
    fun `home sections use eligibility query and bounded article diary queries`() {
        every { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() } returns emptyList()
        every { articleRepository.findTop6ByOrderByPublishDateDesc() } returns emptyList()
        every { diaryRepository.findTop8ByStatusOrderByPublishDateDesc("published") } returns emptyList()

        service.getHotProjects()
        service.getExpertArticles()
        service.getUserDiaries()

        verify(exactly = 1) { institutionProjectRepository.findByIsActiveTrueOrderBySalesCountDesc() }
        verify(exactly = 0) { projectRepository.findTop8ByOrderBySalesCountDesc() }
        verify(exactly = 1) { articleRepository.findTop6ByOrderByPublishDateDesc() }
        verify(exactly = 1) { diaryRepository.findTop8ByStatusOrderByPublishDateDesc("published") }
        verify(exactly = 0) { projectRepository.findAll() }
        verify(exactly = 0) { articleRepository.findAll() }
        verify(exactly = 0) { diaryRepository.findPublishedOrderByPublishDateDesc() }
    }
}
