package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.identity.service.DoctorInstitutionRequestConflictException
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.jpa.repository.Query

class DoctorInstitutionServiceTest {
    private val repository = mockk<DoctorInstitutionRepository>()
    private val institutionRepository = mockk<InstitutionRepository>()
    private val service = DoctorInstitutionService(repository, institutionRepository)

    @Test
    fun `live relationship entity defaults to approved`() {
        val relationship = DoctorInstitutionEntity(
            id = "relationship-1",
            doctorId = "doctor-1",
            institutionId = "institution-1"
        )

        assertEquals("APPROVED", relationship.status)
    }

    @Test
    fun `repository reactivation never writes pending`() {
        val query = requireNotNull(
            DoctorInstitutionRepository::class.java
                .getMethod(
                    "reactivate",
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType
                )
                .getAnnotation(Query::class.java)
        ).value

        assertTrue(query.contains("status = 'APPROVED'"))
        assertFalse(query.contains("status = 'PENDING'"))
    }

    @Test
    fun `sync rejects creating a relationship outside the approved projection`() {
        val current = listOf(relationship("relationship-1", "institution-1"))
        every { repository.findByDoctorIdOrderByCreatedAtAsc("doctor-1") } returns current
        every { institutionRepository.findAllById(any<Iterable<String>>()) } returns listOf(
            institution("institution-1"),
            institution("institution-2")
        )
        every { repository.reactivate(any(), any(), any()) } returns 0
        every { repository.save(any()) } answers { firstArg() }

        assertThrows<DoctorInstitutionRequestConflictException> {
            service.sync("doctor-1", listOf("institution-1", "institution-2"), "institution-1")
        }
    }

    @Test
    fun `sync rejects restoring a revoked relationship outside the request ledger`() {
        val revoked = relationship("relationship-2", "institution-2", status = "REVOKED")
        every { repository.findByDoctorIdOrderByCreatedAtAsc("doctor-1") } returns listOf(revoked)
        every { institutionRepository.findAllById(any<Iterable<String>>()) } returns listOf(institution("institution-2"))
        every { repository.save(any()) } answers { firstArg() }

        assertThrows<DoctorInstitutionRequestConflictException> {
            service.sync("doctor-1", listOf("institution-2"), "institution-2")
        }
    }

    @Test
    fun `sync rejects deleting an approved relationship outside the request ledger`() {
        val current = listOf(
            relationship("relationship-1", "institution-1", primary = true),
            relationship("relationship-2", "institution-2")
        )
        every { repository.findByDoctorIdOrderByCreatedAtAsc("doctor-1") } returns current
        every { institutionRepository.findAllById(any<Iterable<String>>()) } returns listOf(institution("institution-1"))
        every { repository.save(any()) } answers { firstArg() }

        assertThrows<DoctorInstitutionRequestConflictException> {
            service.sync("doctor-1", listOf("institution-1"), "institution-1")
        }
    }

    @Test
    fun `sync only changes primary inside the existing approved set`() {
        var current = listOf(
            relationship("relationship-1", "institution-1", primary = true),
            relationship("relationship-2", "institution-2")
        )
        every { repository.findByDoctorIdOrderByCreatedAtAsc("doctor-1") } answers { current }
        every { institutionRepository.findAllById(any<Iterable<String>>()) } returns listOf(
            institution("institution-1"),
            institution("institution-2")
        )
        every { repository.save(any()) } answers {
            firstArg<DoctorInstitutionEntity>().also { saved ->
                current = current.map { if (it.id == saved.id) saved else it }
            }
        }

        val selection = service.sync(
            "doctor-1",
            listOf("institution-1", "institution-2"),
            "institution-2"
        )

        assertEquals("institution-2", selection.primaryInstitutionId)
        assertEquals(listOf("institution-2"), selection.relations.filter { it.isPrimary }.map { it.institutionId })
        assertTrue(selection.relations.all { it.status == "APPROVED" && it.deletedAt == null })
        verify(exactly = 0) { repository.reactivate(any(), any(), any()) }
    }

    @Test
    fun `omitted relationship inputs leave the approved projection unchanged`() {
        val current = listOf(relationship("relationship-1", "institution-1", primary = true))
        every { repository.findByDoctorIdOrderByCreatedAtAsc("doctor-1") } returns current
        every { institutionRepository.findAllById(any<Iterable<String>>()) } returns listOf(institution("institution-1"))

        val selection = service.sync("doctor-1", emptyList(), null)

        assertEquals("institution-1", selection.primaryInstitutionId)
        assertEquals(current, selection.relations)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.reactivate(any(), any(), any()) }
    }

    private fun relationship(
        id: String,
        institutionId: String,
        status: String = "APPROVED",
        primary: Boolean = false
    ) = DoctorInstitutionEntity(
        id = id,
        doctorId = "doctor-1",
        institutionId = institutionId,
        isPrimary = primary,
        status = status
    )

    private fun institution(id: String) = InstitutionEntity(id = id, name = "Institution $id")
}
