package com.joysong.server.discover.service

import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.repository.PublicDoctorProjectView
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DiscoverServiceTest {
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val doctorRepository = mockk<DoctorRepository>()
    private val service = DiscoverService(
        institutionProjectRepository = institutionProjectRepository,
        projectRepository = projectRepository,
        doctorProjectRepository = doctorProjectRepository,
        doctorRepository = doctorRepository,
        institutionProjectDetailResolver = InstitutionProjectDetailResolver()
    )

    private fun publicBinding(
        doctorId: String,
        institutionProjectId: String,
        price: String
    ): PublicDoctorProjectView = mockk<PublicDoctorProjectView>().also { binding ->
        every { binding.doctorId } returns doctorId
        every { binding.projectId } returns "project-1"
        every { binding.institutionProjectId } returns institutionProjectId
        every { binding.price } returns BigDecimal(price)
    }

    @Test
    fun `institution project directory omits unavailable offerings and uses eligible minimum price`() {
        val available = InstitutionProjectEntity(
            id = "ip-available", institutionId = "institution-1", projectId = "project-1",
            price = BigDecimal("1000")
        )
        val unavailable = InstitutionProjectEntity(
            id = "ip-unavailable", institutionId = "institution-1", projectId = "project-2",
            price = BigDecimal("100")
        )
        every { institutionProjectRepository.findByInstitutionId("institution-1") } returns listOf(available, unavailable)
        every {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-available", "ip-unavailable"))
        } returns listOf(
            publicBinding("doctor-expensive", "ip-available", "900"),
            publicBinding("doctor-affordable", "ip-available", "700")
        )
        every { projectRepository.findAllById(listOf("project-1")) } returns listOf(ProjectEntity("project-1", "Project"))

        val result = service.getInstitutionProjectsWithProject("institution-1")

        assertEquals(listOf("ip-available"), result.map { it.institutionProject.id })
        assertEquals(BigDecimal("700"), result.single().institutionProject.price)
        verify(exactly = 1) {
            doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-available", "ip-unavailable"))
        }
    }

    @Test
    fun `institution project doctor directory uses one public batch query without relationship n plus one`() {
        val institutionProject = InstitutionProjectEntity(
            id = "ip-1", institutionId = "institution-1", projectId = "project-1",
            price = BigDecimal("1000")
        )
        every { institutionProjectRepository.findById("ip-1") } returns java.util.Optional.of(institutionProject)
        every { doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-1")) } returns listOf(
            publicBinding("doctor-1", "ip-1", "700")
        )
        every { doctorRepository.findAllPublicById(listOf("doctor-1")) } returns listOf(DoctorEntity("doctor-1", "Doctor"))

        val result = service.getDoctorsByInstitutionProject("ip-1")

        assertEquals(listOf("doctor-1"), result.map { it.id })
        assertEquals(BigDecimal("700"), result.single().projectPrice)
        verify(exactly = 1) { doctorProjectRepository.findPublicByInstitutionProjectIds(listOf("ip-1")) }
        verify(exactly = 1) { doctorRepository.findAllPublicById(listOf("doctor-1")) }
    }
}
