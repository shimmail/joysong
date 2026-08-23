package com.joysong.server.admin.controller

import com.joysong.server.admin.entity.dto.DoctorProjectBinding
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class InstitutionProjectControllerTest {
    @Test
    fun `create saves two doctors on one institution project with independent prices`() {
        val fixture = Fixture()
        val request = institutionProjectRequest(
            doctorBindings = listOf(
                DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")),
                DoctorProjectBinding("doctor-b", price = BigDecimal("4299.00"))
            )
        )

        fixture.controller.create(fixture.authentication, request)

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.associate { it.doctorId to it.price } == mapOf(
                    "doctor-a" to BigDecimal("3999.00"),
                    "doctor-b" to BigDecimal("4299.00")
                )
            })
        }
        verify(exactly = 2) {
            fixture.configs.save(match {
                it.medicalListPrice in setOf(BigDecimal("3999.00"), BigDecimal("4299.00"))
            })
        }
    }

    @Test
    fun `update changes a retained doctor price without clearing profile fields`() {
        val fixture = Fixture()
        val existing = DoctorProjectEntity(
            doctorId = "doctor-a",
            projectId = "project-1",
            institutionProjectId = "ip-1",
            price = BigDecimal("3000.00"),
            serviceDescription = "keep-description",
            serviceTags = "keep-tags",
            scheduleNote = "keep-schedule",
            coverImage = "keep-cover",
            images = "keep-images"
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns listOf(existing)
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns existing

        fixture.controller.update(
            fixture.authentication,
            "ip-1",
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.single().price == BigDecimal("3999.00") &&
                    rows.single().serviceDescription == "keep-description" &&
                    rows.single().serviceTags == "keep-tags" &&
                    rows.single().scheduleNote == "keep-schedule" &&
                    rows.single().coverImage == "keep-cover" &&
                    rows.single().images == "keep-images"
            })
        }
    }

    @Test
    fun `update uses locked doctor snapshot so concurrently approved profile fields survive`() {
        val fixture = Fixture()
        val stale = doctorProject(
            doctorId = "doctor-a",
            description = "stale-description",
            tags = "stale-tags",
            images = "stale-images"
        )
        val approved = doctorProject(
            doctorId = "doctor-a",
            description = "approved-description",
            tags = "approved-tags",
            images = "approved-images"
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns stale
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns approved.single()

        fixture.controller.update(
            fixture.authentication,
            "ip-1",
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.single().serviceDescription == "approved-description" &&
                    rows.single().serviceTags == "approved-tags" &&
                    rows.single().images == "approved-images"
            })
        }
        verifyOrder {
            fixture.doctorProjects.findForUpdate("doctor-a", "ip-1")
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-a", "ip-1")
        }
    }

    @Test
    fun `update restores soft-deleted compatibility row without clearing legacy fields`() {
        val fixture = Fixture()
        val legacyConfig = DoctorInstitutionProjectConfigEntity(
            id = "config-a",
            doctorId = "doctor-a",
            institutionProjectId = "ip-1",
            consultationFee = BigDecimal("88.00"),
            commissionRate = BigDecimal("12.00"),
            institutionRate = BigDecimal("35.00"),
            medicalListPrice = BigDecimal("3000.00"),
            deletedAt = LocalDateTime.of(2026, 8, 20, 9, 0)
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns emptyList()
        every {
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-a", "ip-1")
        } returns legacyConfig

        fixture.controller.update(
            fixture.authentication,
            "ip-1",
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.configs.save(match {
                it.id == "config-a" && it.medicalListPrice == BigDecimal("3999.00") &&
                    it.consultationFee == BigDecimal("88.00") && it.commissionRate == BigDecimal("12.00") &&
                    it.institutionRate == BigDecimal("35.00") && it.deletedAt == null
            })
        }
    }

    @Test
    fun `removing doctor withdraws pending profile update and leave requests`() {
        val fixture = Fixture()
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns doctorProject("doctor-a")
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns doctorProject("doctor-a").single()

        fixture.controller.update(
            fixture.authentication,
            "ip-1",
            institutionProjectRequest(doctorBindings = emptyList())
        )

        verify {
            fixture.jdbc.update(
                match<String> {
                    it.contains("UPDATE doctor_project_change_requests") &&
                        it.contains("status = 'WITHDRAWN'") &&
                        it.contains("request_type IN ('PROFILE_UPDATE', 'LEAVE')") &&
                        it.contains("status = 'PENDING'")
                },
                "doctor-a",
                "ip-1"
            )
        }
    }

    @Test
    fun `missing doctor price returns an error without saving bindings`() = assertRejectedPrice(null)

    @Test
    fun `zero doctor price returns an error without saving bindings`() = assertRejectedPrice(BigDecimal.ZERO)

    @Test
    fun `fractional cent doctor price returns an error without saving bindings`() = assertRejectedPrice(BigDecimal("3999.001"))

    @Test
    fun `duplicate doctor binding returns an error without saving bindings`() {
        val fixture = Fixture()

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(
                doctorBindings = listOf(
                    DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")),
                    DoctorProjectBinding("doctor-a", price = BigDecimal("4299.00"))
                )
            )
        )

        assertEquals(409, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    @Test
    fun `unrelated institution doctor price returns an error without saving bindings`() {
        val fixture = Fixture(doctorInstitutionId = "institution-other")

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00"))))
        )

        assertEquals(409, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    private fun assertRejectedPrice(price: BigDecimal?) {
        val fixture = Fixture()

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = price)))
        )

        assertNotEquals(200, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    private class Fixture(doctorInstitutionId: String = "institution-1") {
        val authentication = mockk<Authentication>()
        private val institutionProjects = mockk<InstitutionProjectRepository>(relaxed = true)
        val doctorProjects = mockk<DoctorProjectRepository>(relaxed = true)
        private val doctors = mockk<DoctorRepository>()
        private val projects = mockk<ProjectRepository>()
        private val institutions = mockk<InstitutionRepository>()
        val configs = mockk<DoctorInstitutionProjectConfigRepository>(relaxed = true)
        private val orders = mockk<OrderRepository>(relaxed = true)
        private val doctorInstitutions = mockk<DoctorInstitutionService>()
        private val access = mockk<ManagementAccessService>()
        val jdbc = mockk<JdbcTemplate>(relaxed = true)
        private val travelGroundServicePricing = TravelGroundServicePricing(
            OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") })
        )
        val controller = InstitutionProjectController(
            institutionProjects,
            doctorProjects,
            doctors,
            projects,
            institutions,
            configs,
            orders,
            InstitutionProjectDetailResolver(),
            doctorInstitutions,
            access,
            jdbc,
            travelGroundServicePricing
        )

        init {
            val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
            every { access.actor(authentication) } returns actor
            every { access.requirePlatformAdmin(actor) } returns Unit
            every { institutions.existsById("institution-1") } returns true
            every { projects.findById("project-1") } returns Optional.of(ProjectEntity("project-1", "Project"))
            every { institutionProjects.findByInstitutionIdAndProjectId("institution-1", "project-1") } returns null
            every { institutionProjects.findById("ip-1") } returns Optional.of(
                InstitutionProjectEntity("ip-1", "institution-1", "project-1", price = BigDecimal("3500.00"))
            )
            every { institutionProjects.save(any()) } answers { firstArg() }
            every { doctors.findAllById(any<Iterable<String>>()) } answers {
                firstArg<Iterable<String>>().map { DoctorEntity(it, it) }
            }
            every { doctorInstitutions.findByDoctorId(any()) } answers {
                listOf(DoctorInstitutionEntity("di-1", firstArg(), doctorInstitutionId))
            }
            every { configs.findByDoctorIdAndInstitutionProjectIdIncludeDeleted(any(), any()) } returns null
            every { configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(any(), any()) } returns null
            every { configs.save(any<com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity>()) } answers { firstArg() }
            every { doctorProjects.findByInstitutionProjectId("ip-1") } returns emptyList()
            every { doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns emptyList()
            every { doctorProjects.findForUpdate(any(), any()) } returns null
        }
    }

    private fun institutionProjectRequest(doctorBindings: List<DoctorProjectBinding>) = InstitutionProjectRequest(
        institutionId = "institution-1",
        projectId = "project-1",
        price = BigDecimal("3500.00"),
        doctorBindings = doctorBindings
    )

    private fun doctorProject(
        doctorId: String,
        description: String = "description",
        tags: String = "tags",
        images: String = "images"
    ) = listOf(
        DoctorProjectEntity(
            doctorId = doctorId,
            projectId = "project-1",
            institutionProjectId = "ip-1",
            price = BigDecimal("3000.00"),
            serviceDescription = description,
            serviceTags = tags,
            images = images
        )
    )
}
