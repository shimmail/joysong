package com.joysong.server.common.initializer

import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DoctorDataInitializerTest {
    @Test
    fun `seeds two doctor prices for the same institution project`() {
        val doctorRepository = mockk<DoctorRepository>()
        val doctorProjectRepository = mockk<DoctorProjectRepository>()
        val relationshipRepository = mockk<DoctorInstitutionRepository>()
        val savedDoctorProjects = slot<List<com.joysong.server.discover.entity.DoctorProjectEntity>>()
        every { doctorRepository.count() } returns 1L
        every { relationshipRepository.count() } returns 1L
        every { doctorProjectRepository.count() } returns 0L
        every { doctorProjectRepository.saveAll(capture(savedDoctorProjects)) } answers { savedDoctorProjects.captured }

        DoctorDataInitializer(
            doctorRepository,
            doctorProjectRepository,
            relationshipRepository
        ).run(emptyArray())

        val sameProject = savedDoctorProjects.captured.filter { it.institutionProjectId == SeedIds.IP_ID_1 }
        assertEquals(
            mapOf(
                SeedIds.DOC_ID_1 to BigDecimal("3999.00"),
                SeedIds.DOC_ID_2 to BigDecimal("4299.00")
            ),
            sameProject.associate { it.doctorId to it.price }
        )
    }

    @Test
    fun `order three initializer seeds explicit approved relationships without an authorization gated service`() {
        val doctorRepository = mockk<DoctorRepository>()
        val doctorProjectRepository = mockk<DoctorProjectRepository>()
        val relationshipRepository = mockk<DoctorInstitutionRepository>()
        val saved = slot<List<DoctorInstitutionEntity>>()
        every { doctorRepository.count() } returns 1L
        every { doctorRepository.findAll() } returns listOf(
            DoctorEntity(
                id = SeedIds.DOC_ID_1,
                name = "Seed Doctor",
                institutionId = SeedIds.INST_ID_1,
                institutionName = "Seed Institution"
            )
        )
        every { relationshipRepository.count() } returns 0L
        every { relationshipRepository.saveAll(capture(saved)) } answers { saved.captured }
        every { doctorProjectRepository.count() } returns 1L

        DoctorDataInitializer(
            doctorRepository,
            doctorProjectRepository,
            relationshipRepository
        ).run(emptyArray())

        assertEquals(setOf(SeedIds.INST_ID_1, SeedIds.INST_ID_3), saved.captured.map { it.institutionId }.toSet())
        assertTrue(saved.captured.all { it.status == "APPROVED" })
        assertEquals(listOf(SeedIds.INST_ID_1), saved.captured.filter { it.isPrimary }.map { it.institutionId })
        assertFalse(
            DoctorDataInitializer::class.java.constructors
                .flatMap { it.parameterTypes.asList() }
                .contains(DoctorInstitutionService::class.java)
        )
    }
}
