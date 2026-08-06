package com.joysong.server.discover.service

import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiscoverDetailServiceTest {
    private val doctorRepository = mockk<DoctorRepository>()
    private val doctorInstitutionService = mockk<DoctorInstitutionService>()
    private val service = DiscoverDetailService(
        doctorRepository = doctorRepository,
        projectRepository = mockk<ProjectRepository>(),
        diaryRepository = mockk<DiaryRepository>(),
        institutionRepository = mockk<InstitutionRepository>(),
        doctorProjectRepository = mockk<DoctorProjectRepository>(),
        reviewRepository = mockk<ReviewRepository>(),
        institutionProjectRepository = mockk<InstitutionProjectRepository>(),
        userRepository = mockk<UserRepository>(),
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
}
