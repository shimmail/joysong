package com.joysong.server.discover.service

import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.repository.PublicDoctorProjectView
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

class DiscoverDetailServiceTest {
    private val doctorRepository = mockk<DoctorRepository>()
    private val doctorInstitutionService = mockk<DoctorInstitutionService>()
    private val projectRepository = mockk<ProjectRepository>()
    private val diaryRepository = mockk<DiaryRepository>()
    private val institutionRepository = mockk<InstitutionRepository>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val reviewRepository = mockk<ReviewRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val userRepository = mockk<UserRepository>()
    private val service = DiscoverDetailService(
        doctorRepository = doctorRepository,
        projectRepository = projectRepository,
        diaryRepository = diaryRepository,
        institutionRepository = institutionRepository,
        doctorProjectRepository = doctorProjectRepository,
        reviewRepository = reviewRepository,
        institutionProjectRepository = institutionProjectRepository,
        userRepository = userRepository,
        institutionProjectDetailResolver = InstitutionProjectDetailResolver(),
        doctorInstitutionService = doctorInstitutionService
    )

    private fun publicBinding(
        doctorId: String,
        institutionProjectId: String,
        projectId: String = "project-1",
        price: String = "700"
    ): PublicDoctorProjectView = mockk<PublicDoctorProjectView>().also { binding ->
        every { binding.doctorId } returns doctorId
        every { binding.projectId } returns projectId
        every { binding.institutionProjectId } returns institutionProjectId
        every { binding.price } returns BigDecimal(price)
    }

    @Test
    fun `doctor detail excludes inactive doctor project services`() {
        val doctor = DoctorEntity(id = "doctor-1", name = "Doctor")
        every { doctorRepository.findPublicById("doctor-1") } returns Optional.of(doctor)
        every { doctorProjectRepository.findPublicByDoctorId("doctor-1") } returns emptyList()
        every { institutionProjectRepository.findAllById(emptyList<String>()) } returns emptyList()
        every { projectRepository.findAllById(emptyList<String>()) } returns emptyList()
        every { institutionRepository.findAllById(emptyList<String>()) } returns emptyList()
        every { diaryRepository.findPublishedByDoctorId("doctor-1") } returns emptyList()
        every { doctorInstitutionService.institutionsFor("doctor-1") } returns emptyList()
        every { reviewRepository.findByDoctorIdAndTargetType("doctor-1", "INSTITUTION") } returns emptyList()

        val result = service.getDoctorDetail("doctor-1")!!

        assertEquals(0, result.institutionProjects.size)
        verify(exactly = 0) { doctorProjectRepository.findByDoctorId(any()) }
    }

    @Test
    fun `doctor detail institution project summary exposes institution project case count`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-1",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000"),
            caseCount = 23
        )
        every { doctorRepository.findPublicById("doctor-1") } returns Optional.of(
            DoctorEntity(id = "doctor-1", name = "Doctor")
        )
        every { doctorProjectRepository.findPublicByDoctorId("doctor-1") } returns listOf(
            publicBinding("doctor-1", "ip-1")
        )
        every { institutionProjectRepository.findAllById(listOf("ip-1")) } returns listOf(institutionProject)
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(
            ProjectEntity(id = "project-1", name = "Project", caseCount = 82)
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns listOf(
            InstitutionEntity(id = "institution-1", name = "Institution")
        )
        every { diaryRepository.findPublishedByDoctorId("doctor-1") } returns emptyList()
        every { doctorInstitutionService.institutionsFor("doctor-1") } returns emptyList()
        every { reviewRepository.findByDoctorIdAndTargetType("doctor-1", "INSTITUTION") } returns emptyList()

        val result = service.getDoctorDetail("doctor-1")!!

        assertEquals(23, result.institutionProjects.single().caseCount)
    }

    @Test
    fun `platform project detail is omitted when no institution offering has an eligible doctor`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-unavailable",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000")
        )
        every { projectRepository.findById("project-1") } returns Optional.of(ProjectEntity("project-1", "Project"))
        every { institutionProjectRepository.findByProjectId("project-1") } returns listOf(institutionProject)
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-unavailable"))
        } returns emptyList()
        every { institutionRepository.findAllById(emptyList<String>()) } returns emptyList()
        every { diaryRepository.findPublishedByProjectId("project-1") } returns emptyList()

        val result = service.getProjectDetail("project-1")

        assertNull(result)
    }

    @Test
    fun `platform project detail uses minimum eligible doctor price`() {
        val expensiveOffering = InstitutionProjectEntity(
            id = "ip-expensive",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000")
        )
        val affordableOffering = InstitutionProjectEntity(
            id = "ip-affordable",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("800")
        )
        every { projectRepository.findById("project-1") } returns Optional.of(
            ProjectEntity("project-1", "Project", referencePrice = BigDecimal("1200"))
        )
        every { institutionProjectRepository.findByProjectId("project-1") } returns
            listOf(expensiveOffering, affordableOffering)
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-expensive", "ip-affordable"))
        } returns listOf(
            publicBinding("doctor-expensive", "ip-expensive", price = "900"),
            publicBinding("doctor-affordable", "ip-affordable", price = "650")
        )
        every { institutionRepository.findAllById(listOf("institution-1")) } returns listOf(
            InstitutionEntity("institution-1", "Institution")
        )
        every { diaryRepository.findPublishedByProjectId("project-1") } returns emptyList()
        every { reviewRepository.findByInstitutionProjectId("ip-expensive") } returns emptyList()
        every { reviewRepository.findByInstitutionProjectId("ip-affordable") } returns emptyList()

        val result = service.getProjectDetail("project-1")!!

        assertEquals(BigDecimal("650"), result.project.referencePrice)
    }

    @Test
    fun `direct institution project detail retains shared content but exposes availability explicitly`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-1",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000")
        )
        every {
            institutionProjectRepository.findByInstitutionIdAndProjectId("institution-1", "project-1")
        } returns institutionProject
        every { projectRepository.findById("project-1") } returns Optional.of(ProjectEntity("project-1", "Project"))
        every { institutionRepository.findById("institution-1") } returns Optional.of(
            InstitutionEntity("institution-1", "Institution")
        )
        every { diaryRepository.findPublishedByProjectId("project-1") } returns emptyList()
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-1")) } returns emptyList()
        every { doctorRepository.findAllPublicById(emptyList<String>()) } returns emptyList()
        every { reviewRepository.findByInstitutionProjectId("ip-1") } returns emptyList()

        val unavailable = service.getInstitutionProjectDetail("institution-1", "project-1")!!

        assertFalse(unavailable.hasAvailableDoctors)
        assertEquals(emptyList<DoctorEntity>(), unavailable.doctors)
        assertEquals(BigDecimal("1000"), unavailable.institutionProject.price)

        val affordable = publicBinding("doctor-affordable", "ip-1", price = "700")
        val expensive = publicBinding("doctor-expensive", "ip-1", price = "900")
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-1")) } returns listOf(expensive, affordable)
        every { doctorRepository.findAllPublicById(listOf("doctor-expensive", "doctor-affordable")) } returns listOf(
            DoctorEntity("doctor-expensive", "Expensive"),
            DoctorEntity("doctor-affordable", "Affordable")
        )

        val available = service.getInstitutionProjectDetail("institution-1", "project-1")!!

        assertTrue(available.hasAvailableDoctors)
        assertEquals(listOf("doctor-expensive", "doctor-affordable"), available.doctors.map { it.id })
        assertEquals(BigDecimal("700"), available.institutionProject.price)
    }

    @Test
    fun `institution doctors are loaded from many-to-many relations`() {
        val secondaryInstitutionDoctor = DoctorEntity(
            id = "doctor-2",
            name = "李医生",
            institutionId = "legacy-primary-institution"
        )
        every { doctorInstitutionService.findByInstitutionId("institution-2") } returns listOf(
            DoctorInstitutionEntity(
                id = "relation-1",
                doctorId = secondaryInstitutionDoctor.id,
                institutionId = "institution-2"
            )
        )
        every { doctorRepository.findAllPublicById(listOf(secondaryInstitutionDoctor.id)) } returns listOf(
            secondaryInstitutionDoctor
        )

        val result = service.getInstitutionDoctors("institution-2")

        assertEquals(listOf(secondaryInstitutionDoctor.toResponse()), result)
        verify(exactly = 0) { doctorRepository.findByInstitutionId(any()) }
    }

    @Test
    fun `institution detail projects use minimum doctor price and exclude projects without doctor bindings`() {
        val pricedProject = InstitutionProjectEntity(
            id = "ip-priced",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("1000"),
            caseCount = 31
        )
        val unboundProject = InstitutionProjectEntity(
            id = "ip-unbound",
            institutionId = "institution-1",
            projectId = "project-2",
            price = BigDecimal("100")
        )
        every { institutionRepository.findById("institution-1") } returns
            Optional.of(InstitutionEntity("institution-1", "Institution"))
        every { institutionProjectRepository.findByInstitutionId("institution-1") } returns
            listOf(pricedProject, unboundProject)
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(
            ProjectEntity("project-1", "Priced Project", caseCount = 74)
        )
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-priced", "ip-unbound"))
        } returns listOf(
            publicBinding("doctor-expensive", "ip-priced", price = "900"),
            publicBinding("doctor-affordable", "ip-priced", price = "700")
        )
        every { doctorInstitutionService.findByInstitutionId("institution-1") } returns emptyList()
        every { diaryRepository.findPublishedByInstitutionId("institution-1") } returns emptyList()
        every { reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", "institution-1") } returns emptyList()

        val result = service.getInstitutionDetail("institution-1")!!

        assertEquals(listOf("ip-priced"), result.projects.map { it.institutionProjectId })
        assertEquals(BigDecimal("700"), result.projects.single().price)
        assertEquals(31, result.projects.single().caseCount)
    }
}
