package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.config.OrderSplitProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class DoctorProjectChangeServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
    private val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
    private val splitRatePolicy = OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("10.00"); institutionRate = BigDecimal("40.00") })
    private val service = DoctorProjectChangeService(jdbcTemplate, doctorProjectRepository, configRepository, relationshipService, splitRatePolicy, jacksonObjectMapper())

    @Test
    fun `profile update targets expose only current doctor approved active bindings`() {
        every {
            jdbcTemplate.query(
                match<String> {
                    it.contains("dp.doctor_id = ?") &&
                        it.contains("di.status = 'APPROVED'") &&
                        it.contains("di.revoked_at IS NULL") &&
                        it.contains("di.deleted_at IS NULL") &&
                        it.contains("ip.deleted_at IS NULL") &&
                        it.contains("c.doctor_id = dp.doctor_id") &&
                        it.contains("c.institution_project_id = dp.institution_project_id")
                },
                any<RowMapper<Any>>(),
                "doctor-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>(relaxed = true) {
                every { getString("institution_project_id") } returns "ip-1"
                every { getString("project_name") } returns "Project"
                every { getString("institution_id") } returns "institution-1"
                every { getString("institution_name") } returns "Institution"
                every { getBigDecimal("current_price") } returns BigDecimal("880.00")
                every { getString("service_description") } returns "service"
                every { getString("service_tags") } returns "tag-a,tag-b"
                every { getString("schedule_note") } returns "schedule"
                every { getString("cover_image") } returns "cover"
                every { getString("images") } returns "image-a,image-b"
                every { getBigDecimal("consultation_fee") } returns BigDecimal("30.00")
                every { getBigDecimal("commission_rate") } returns BigDecimal("10.00")
                every { getBigDecimal("institution_rate") } returns BigDecimal("40.00")
            }
            listOf(mapper.mapRow(rs, 0))
        }

        val target = service.listProfileUpdateTargets(doctorActor()).single()

        assertEquals("ip-1", target.institutionProjectId)
        assertEquals(BigDecimal("880.00"), target.currentPrice)
        assertEquals(listOf("tag-a", "tag-b"), target.serviceTags)
        assertEquals(BigDecimal("10.00"), target.platformRate)
        assertEquals(BigDecimal("40.00"), target.doctorRate)
    }

    @Test
    fun `profile update targets reject admin without authenticated doctor identity`() {
        assertThrows(AccessDeniedException::class.java) {
            service.listProfileUpdateTargets(adminActor())
        }
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `profile update target without config uses system defaults`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), "doctor-1") } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>(relaxed = true) {
                every { getString("institution_project_id") } returns "ip-1"
                every { getString("project_name") } returns "Project"
                every { getString("institution_id") } returns "institution-1"
                every { getString("institution_name") } returns "Institution"
                every { getBigDecimal("current_price") } returns BigDecimal("880.00")
                every { getBigDecimal("consultation_fee") } returns null
                every { getBigDecimal("commission_rate") } returns null
                every { getBigDecimal("institution_rate") } returns null
            }
            listOf(mapper.mapRow(rs, 0))
        }

        val target = service.listProfileUpdateTargets(doctorActor()).single()

        assertEquals(BigDecimal.ZERO, target.consultationFee)
        assertEquals(BigDecimal.ZERO, target.commissionRate)
        assertEquals(BigDecimal("40.00"), target.institutionRate)
        assertEquals(BigDecimal("10.00"), target.platformRate)
        assertEquals(BigDecimal("50.00"), target.doctorRate)
    }

    @Test
    fun `profile update requires complete snapshot values`() {
        val request = DoctorProjectChangeRequest(
            institutionProjectId = "ip-1", requestType = "PROFILE_UPDATE",
            serviceDescription = "service", priceSuggestion = BigDecimal("880.00"),
            notes = "notes", serviceTags = listOf("tag"), scheduleNote = "schedule",
            coverImage = "cover", images = listOf("image"), consultationFee = BigDecimal("30.00"),
            commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00")
        )
        assertEquals(listOf("tag"), request.serviceTags)
        assertEquals(BigDecimal("30.00"), request.consultationFee)
    }

    @Test
    fun `profile update request view exposes immutable before snapshot`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = viewResultSet("PENDING")
            every { rs.getString("request_type") } returns "PROFILE_UPDATE"
            every { rs.getBigDecimal("current_price") } returns BigDecimal("700.00")
            every { rs.getString("current_service_description") } returns "before service"
            every { rs.getString("current_service_tags") } returns "before-a,before-b"
            every { rs.getString("current_schedule_note") } returns "before schedule"
            every { rs.getString("current_cover_image") } returns "before-cover"
            every { rs.getString("current_images") } returns "before-image"
            every { rs.getBigDecimal("current_consultation_fee") } returns BigDecimal("20.00")
            every { rs.getBigDecimal("current_commission_rate") } returns BigDecimal("5.00")
            every { rs.getBigDecimal("current_institution_rate") } returns BigDecimal("35.00")
            every { rs.getBigDecimal("current_platform_rate") } returns BigDecimal("10.00")
            every { rs.getBigDecimal("current_doctor_rate") } returns BigDecimal("50.00")
            listOf(mapper.mapRow(rs, 0))
        }

        val view = service.list(doctorActor()).single()

        assertEquals(BigDecimal("700.00"), view.currentPrice)
        assertEquals("before service", view.currentServiceDescription)
        assertEquals(listOf("before-a", "before-b"), view.currentServiceTags)
        assertEquals(BigDecimal("20.00"), view.currentConsultationFee)
        assertEquals(BigDecimal("10.00"), view.currentPlatformRate)
        assertEquals(BigDecimal("50.00"), view.currentDoctorRate)
    }

    @Test
    fun `legal representative cannot force profile approval`() {
        assertThrows(AccessDeniedException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "force", true)
        }
    }

    @Test
    fun `profile approval rejects drift before writing either effective row`() {
        stubReviewQueries(requestType = "PROFILE_UPDATE")
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject(updatedAt = LocalDateTime.of(2026, 8, 11, 10, 0))

        assertThrows(DoctorProjectChangeConflictException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
    }

    @Test
    fun `admin force applies exact doctor profile and records force audit`() {
        stubReviewQueries(requestType = "PROFILE_UPDATE")
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns DoctorInstitutionProjectConfigEntity(id="config-1", doctorId="doctor-1", institutionProjectId="ip-1")
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        service.review(adminActor(), "request-1", "APPROVED", "override drift", true)

        val project = slot<DoctorProjectEntity>()
        verify(exactly = 1) { doctorProjectRepository.save(capture(project)) }
        assertEquals(BigDecimal("880.00"), project.captured.price)
        verify(exactly = 1) { configRepository.save(match { it.doctorId == "doctor-1" && it.consultationFee == BigDecimal("30.00") }) }
        verify(exactly = 1) { jdbcTemplate.update(match<String> { it.contains("force_processed = ?") }, *anyVararg()) }
    }

    @Test
    fun `join submission rejects legacy profile fields outside minimal contract`() {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM institution_projects") },
                any<RowMapper<Any>>(),
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns null

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(
                doctorActor(),
                DoctorProjectChangeRequest(
                    institutionProjectId = "ip-1",
                    requestType = "JOIN",
                    serviceDescription = "service",
                    priceSuggestion = BigDecimal("880.00"),
                    notes = "notes",
                    serviceTags = listOf("legacy-tag")
                )
            )
        }

        assertEquals("加入机构项目仅允许提交服务内容、价格建议和说明", error.message)
    }

    @Test
    fun `join approval stores doctor price and submitted service content`() {
        stubReviewQueries()
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.review(legalActor(), "request-1", "APPROVED", "", false)

        val binding = slot<DoctorProjectEntity>()
        verify { doctorProjectRepository.save(capture(binding)) }
        verify { relationshipService.requireActiveRelationshipForUpdate("doctor-1", "institution-1") }
        assertEquals(BigDecimal("880.00"), binding.captured.price)
        assertEquals("service", binding.captured.serviceDescription)
        assertEquals("", binding.captured.serviceTags)
        assertEquals("", binding.captured.scheduleNote)
        assertEquals("", binding.captured.coverImage)
        assertEquals("", binding.captured.images)
        assertEquals("APPROVED", result.status)
    }

    @Test
    fun `join review supports changes requested without changing doctor binding`() {
        stubReviewQueries(status = "CHANGES_REQUESTED")
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.review(legalActor(), "request-1", "CHANGES_REQUESTED", "请修改价格", false)

        assertEquals("CHANGES_REQUESTED", result.status)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    @Test
    fun `join approval rejects doctor whose institution relationship was revoked`() {
        stubReviewQueries()
        every { relationshipService.requireActiveRelationshipForUpdate("doctor-1", "institution-1") } throws
            org.springframework.security.access.AccessDeniedException("医生与机构的有效执业关系已失效")

        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    private fun stubReviewQueries(status: String = "APPROVED", requestType: String = "JOIN") {
        every {
            jdbcTemplate.queryForObject(match<String> { it.contains("doctor_institutions") }, Long::class.java, *anyVararg())
        } returns 1L
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(viewResultSet(status), 0))
        }
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val mapper = secondArg<RowMapper<Any>>()
            val rs = if (sql.contains("FOR UPDATE")) targetResultSet(requestType) else viewResultSet(status)
            listOf(mapper.mapRow(rs, 0))
        }
    }

    private fun targetResultSet(requestType: String = "JOIN"): ResultSet = mockk(relaxed = true) {
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("project_id") } returns "project-1"
        every { getString("request_type") } returns requestType
        every { getString("service_description") } returns "service"
        every { getString("service_tags") } returns "tag"
        every { getString("schedule_note") } returns "schedule"
        every { getString("cover_image") } returns ""
        every { getString("images") } returns ""
        every { getBigDecimal("price_suggestion") } returns BigDecimal("880.00")
        every { getString("notes") } returns "doctor notes"
        every { getString("status") } returns "PENDING"
        every { getString("submitted_by") } returns "doctor-1"
        every { getBigDecimal("consultation_fee") } returns BigDecimal("30.00")
        every { getBigDecimal("commission_rate") } returns BigDecimal("10.00")
        every { getBigDecimal("institution_rate") } returns BigDecimal("40.00")
        every { getTimestamp("base_doctor_project_updated_at") } returns Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        every { getString("base_config_id") } returns "config-1"
        every { getTimestamp("base_config_updated_at") } returns Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
    }

    private fun viewResultSet(status: String): ResultSet = mockk(relaxed = true) {
        val now = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        every { getString("id") } returns "request-1"
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("doctor_name") } returns "Doctor"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_name") } returns "Institution"
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("project_name") } returns "Project"
        every { getString("request_type") } returns "JOIN"
        every { getString("service_description") } returns "service"
        every { getString("service_tags") } returns "tag"
        every { getString("schedule_note") } returns "schedule"
        every { getString("cover_image") } returns ""
        every { getString("images") } returns ""
        every { getBigDecimal("price_suggestion") } returns BigDecimal("880.00")
        every { getString("notes") } returns "doctor notes"
        every { getString("status") } returns status
        every { getString("submitted_by") } returns "doctor-1"
        every { getString("reviewed_by") } returns "legal-1"
        every { getString("reviewer_name") } returns "Legal"
        every { getString("review_note") } returns ""
        every { getTimestamp("submitted_at") } returns now
        every { getTimestamp("reviewed_at") } returns now
        every { getTimestamp("updated_at") } returns now
    }

    private fun legalActor() = ManagementActor(
        userId = "legal-1",
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = setOf("institution-1"),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun adminActor() = legalActor().copy(userId="admin-1", isAdmin=true, managedInstitutionIds=emptySet())

    private fun doctorProject(updatedAt: LocalDateTime = LocalDateTime.of(2026, 8, 10, 10, 0)) = DoctorProjectEntity(
        doctorId="doctor-1", projectId="project-1", institutionProjectId="ip-1", price=BigDecimal("100.00"), updatedAt=updatedAt
    )

    private fun doctorActor() = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf("institution-1"),
        manageableDoctorIds = setOf("doctor-1")
    )
}
