package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.identity.service.ManagementActor
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
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

class DoctorProjectChangeServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
    private val service = DoctorProjectChangeService(jdbcTemplate, doctorProjectRepository, configRepository)

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
                    serviceTags = "legacy-tag"
                )
            )
        }

        assertEquals("加入机构项目仅允许提交服务内容、价格建议和说明", error.message)
    }

    @Test
    fun `join approval stores doctor price and submitted service content`() {
        stubReviewQueries()
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.review(legalActor(), "request-1", "APPROVED", "")

        val binding = slot<DoctorProjectEntity>()
        verify { doctorProjectRepository.save(capture(binding)) }
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

        val result = service.review(legalActor(), "request-1", "CHANGES_REQUESTED", "请修改价格")

        assertEquals("CHANGES_REQUESTED", result.status)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    @Test
    fun `join approval rejects doctor whose institution relationship was revoked`() {
        stubReviewQueries()
        every {
            jdbcTemplate.queryForObject(match<String> { it.contains("doctor_institutions") }, Long::class.java, *anyVararg())
        } returns 0L

        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "")
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    private fun stubReviewQueries(status: String = "APPROVED") {
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
            val rs = if (sql.contains("FOR UPDATE")) targetResultSet() else viewResultSet(status)
            listOf(mapper.mapRow(rs, 0))
        }
    }

    private fun targetResultSet(): ResultSet = mockk(relaxed = true) {
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("project_id") } returns "project-1"
        every { getString("request_type") } returns "JOIN"
        every { getString("service_description") } returns "service"
        every { getString("service_tags") } returns "tag"
        every { getString("schedule_note") } returns "schedule"
        every { getString("cover_image") } returns ""
        every { getString("images") } returns ""
        every { getBigDecimal("price_suggestion") } returns BigDecimal("880.00")
        every { getString("notes") } returns "doctor notes"
        every { getString("status") } returns "PENDING"
        every { getString("submitted_by") } returns "doctor-1"
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
