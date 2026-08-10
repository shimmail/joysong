package com.joysong.server.discover.service

import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.discover.repository.DoctorProjectRepository
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
        every { doctorRepository.findAllById(listOf(secondaryInstitutionDoctor.id)) } returns listOf(
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
            price = BigDecimal("1000")
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
        every { projectRepository.findAllById(listOf("project-1", "project-2")) } returns listOf(
            ProjectEntity("project-1", "Priced Project"),
            ProjectEntity("project-2", "Unbound Project")
        )
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
        every { doctorInstitutionService.findByInstitutionId("institution-1") } returns emptyList()
        every { diaryRepository.findPublishedByInstitutionId("institution-1") } returns emptyList()
        every { reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", "institution-1") } returns emptyList()

        val result = service.getInstitutionDetail("institution-1")!!

        assertEquals(listOf("ip-priced"), result.projects.map { it.institutionProjectId })
        assertEquals(BigDecimal("700"), result.projects.single().price)
    }
}
