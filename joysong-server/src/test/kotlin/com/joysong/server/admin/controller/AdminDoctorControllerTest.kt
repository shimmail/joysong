package com.joysong.server.admin.controller

import com.joysong.server.catalog.service.CatalogIntegrityService
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.doctor.service.DoctorService
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.identity.service.DoctorInstitutionRequestConflictException
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.InstitutionService
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.Authentication

class AdminDoctorControllerTest {

    @Test
    fun `doctor creation cannot turn institution fields into a live relationship`() {
        val doctorService = mockk<DoctorService>()
        val doctorInstitutionService = mockk<DoctorInstitutionService>()
        val userRepository = mockk<UserRepository>()
        every { userRepository.existsById("doctor-1") } returns true
        every { doctorService.findById("doctor-1") } returns null
        every { doctorService.save(any()) } answers { firstArg() }
        every {
            doctorInstitutionService.sync("doctor-1", listOf("institution-1"), "institution-1")
        } throws DoctorInstitutionRequestConflictException("医生机构关系只能通过关系申请变更")
        val controller = AdminDoctorController(
            doctorService,
            mockk(relaxed = true),
            doctorInstitutionService,
            mockk(relaxed = true),
            userRepository,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )

        assertThrows<DoctorInstitutionRequestConflictException> {
            controller.createDoctor(
                DoctorAdminRequest(userId = "doctor-1", institutionId = "institution-1")
            )
        }

        verify(exactly = 1) {
            doctorInstitutionService.sync("doctor-1", listOf("institution-1"), "institution-1")
        }
    }

    @Test
    fun `admin doctor update cannot add an institution outside the request ledger`() {
        val doctorService = mockk<DoctorService>()
        val doctorInstitutionService = mockk<DoctorInstitutionService>()
        val userRepository = mockk<UserRepository>()
        val accessService = mockk<ManagementAccessService>(relaxed = true)
        val authentication = mockk<Authentication>()
        val actor = ManagementActor(
            "admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet()
        )
        every { accessService.actor(authentication) } returns actor
        every { userRepository.existsById("doctor-1") } returns true
        every { doctorService.findById("doctor-1") } returns DoctorEntity(id = "doctor-1", name = "Doctor")
        every { doctorService.save(any()) } answers { firstArg() }
        every {
            doctorInstitutionService.sync("doctor-1", listOf("institution-1"), "institution-1")
        } throws DoctorInstitutionRequestConflictException("医生机构关系只能通过关系申请变更")
        val controller = AdminDoctorController(
            doctorService,
            mockk(relaxed = true),
            doctorInstitutionService,
            mockk(relaxed = true),
            userRepository,
            mockk(relaxed = true),
            accessService
        )

        assertThrows<DoctorInstitutionRequestConflictException> {
            controller.updateDoctor(
                authentication,
                "doctor-1",
                DoctorAdminRequest(userId = "doctor-1", institutionIds = listOf("institution-1"), primaryInstitutionId = "institution-1")
            )
        }
    }

    @Test
    fun `doctor profile update preserves platform managed counters`() {
        val doctorService = mockk<DoctorService>()
        val doctorInstitutionService = mockk<DoctorInstitutionService>()
        val userRepository = mockk<UserRepository>()
        val accessService = mockk<ManagementAccessService>()
        val authentication = mockk<Authentication>()
        val actor = ManagementActor(
            userId = "doctor-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR"),
            doctorId = "doctor-1",
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = setOf("doctor-1")
        )
        val existing = DoctorEntity(
            id = "doctor-1",
            name = "原姓名",
            consultationCount = 12,
            caseCount = 7,
            reviewCount = 5
        )
        val saved = slot<DoctorEntity>()
        every { accessService.actor(authentication) } returns actor
        justRun { accessService.requireDoctor(actor, "doctor-1") }
        every { doctorService.findById("doctor-1") } returns existing
        every { userRepository.existsById("doctor-1") } returns true
        every { doctorService.save(capture(saved)) } answers { saved.captured }
        every { doctorInstitutionService.institutionsFor("doctor-1") } returns emptyList()
        val controller = AdminDoctorController(
            doctorService = doctorService,
            institutionService = mockk<InstitutionService>(),
            doctorInstitutionService = doctorInstitutionService,
            catalogIntegrityService = mockk<CatalogIntegrityService>(),
            userRepository = userRepository,
            identityAuthorizationService = mockk<IdentityAuthorizationService>(),
            managementAccessService = accessService
        )

        controller.updateDoctor(
            authentication,
            "doctor-1",
            DoctorAdminRequest(userId = "doctor-1", name = "新姓名")
        )

        assertEquals(12, saved.captured.consultationCount)
        assertEquals(7, saved.captured.caseCount)
        assertEquals(5, saved.captured.reviewCount)
    }
}
