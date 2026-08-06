package com.joysong.server.discover.service

import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscoverSearchServiceTest {
    private val institutionRepository = mockk<InstitutionRepository>()
    private val service = DiscoverSearchService(
        projectRepository = mockk<ProjectRepository>(),
        institutionRepository = institutionRepository,
        institutionProjectRepository = mockk<InstitutionProjectRepository>(),
        doctorRepository = mockk<DoctorRepository>(),
        keywordExtractor = DiscoverKeywordExtractor(),
        doctorInstitutionService = mockk<DoctorInstitutionService>(),
        institutionProjectDetailResolver = InstitutionProjectDetailResolver()
    )

    @Test
    fun `matching fragments keep custom terms and remove conversational noise`() {
        every { institutionRepository.findAll() } returns emptyList()

        val fragments = service.extractMatchingFragments("我想了解提亮肤色项目怎么样")

        assertTrue("提亮" in fragments)
        assertTrue("肤色" in fragments)
        assertFalse("我想" in fragments)
        assertFalse("怎么" in fragments)
    }
}
