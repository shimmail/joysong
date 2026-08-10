package com.joysong.server.project.service

import com.joysong.server.identity.service.ManagementActor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import java.math.BigDecimal
import java.sql.ResultSet

class ProfessionalProjectRequestServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val service = ProfessionalProjectRequestService(jdbcTemplate)

    @Test
    fun `doctor submits a platform project request after duplicate check`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1

        val result = service.submitPlatform(
            doctorActor(),
            PlatformProjectRequestSubmission("  Laser  ", " Skin ", " Description ", " Notes ")
        )

        assertEquals("PLATFORM", result.requestType)
        assertEquals("PENDING", result.status)
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO professional_project_requests") },
                any(), "PLATFORM", "doctor-1", null, null, "Laser", "Skin", "Description", null,
                null, "Notes"
            )
        }
    }

    @Test
    fun `doctor cannot submit an institution request outside approved institutions`() {
        val error = assertThrows<AccessDeniedException> {
            service.submitInstitution(
                doctorActor(approvedInstitutions = setOf("institution-1")),
                "institution-2",
                InstitutionProjectRequestSubmission("project-1", "service", BigDecimal("99.00"), "notes")
            )
        }

        assertEquals("只能向已通过执业关系的机构提交项目申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `same pending institution request is rejected before insert`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 1L

        val error = assertThrows<IllegalArgumentException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                InstitutionProjectRequestSubmission("project-1", "service", BigDecimal("99.00"), "notes")
            )
        }

        assertEquals("同一项目已有待处理申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution request is rejected when institution already has base project`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 1L

        val error = assertThrows<IllegalArgumentException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                InstitutionProjectRequestSubmission("project-1", "service", BigDecimal("99.00"), "notes")
            )
        }

        assertEquals("该机构已存在此平台项目，请申请加入机构项目", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `rejected and changes requested decisions require review note before database access`() {
        listOf("REJECTED", "CHANGES_REQUESTED").forEach { decision ->
            val error = assertThrows<IllegalArgumentException> {
                service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview(decision, "  "))
            }
            assertEquals("拒绝或要求修改时必须填写审核意见", error.message)
        }
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `institution reviewer cannot review another institution request`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-2"), 0))
        }

        val error = assertThrows<AccessDeniedException> {
            service.reviewInstitution(
                legalRepresentativeActor(setOf("institution-1")),
                "request-1",
                ProjectRequestReview("APPROVED")
            )
        }

        assertEquals("只能审核本机构的项目申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution approval creates institution project binds doctor and closes request`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-1"), 0))
        }
        every { jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg()) } answers {
            if (firstArg<String>().contains("institution_projects")) 0L else 1L
        }
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns "institution-1"
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.reviewInstitution(
            legalRepresentativeActor(setOf("institution-1")),
            "request-1",
            ProjectRequestReview("APPROVED")
        )

        assertEquals("APPROVED", result.status)
        verify(exactly = 1) {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO institution_projects") }, *anyVararg())
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO doctor_projects") && it.contains("price") },
                "doctor-1", "project-1", any(), "Service", "", BigDecimal("99.00")
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(match<String> { it.contains("UPDATE professional_project_requests") }, *anyVararg())
        }
    }

    @Test
    fun `institution approval revalidates current doctor membership`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-1"), 0))
        }
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns "institution-1"
        every { jdbcTemplate.queryForObject(match<String> { it.contains("doctor_institutions") }, Long::class.java, *anyVararg()) } returns 0L

        val error = assertThrows<IllegalArgumentException> {
            service.reviewInstitution(
                legalRepresentativeActor(setOf("institution-1")),
                "request-1",
                ProjectRequestReview("APPROVED")
            )
        }

        assertEquals("医生已不具备该机构的有效执业关系", error.message)
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO institution_projects") }, *anyVararg()) }
    }

    private fun targetResultSet(type: String, institutionId: String?): ResultSet = mockk<ResultSet>().also { rs ->
        every { rs.getString("id") } returns "request-1"
        every { rs.getString("request_type") } returns type
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns institutionId
        every { rs.getString("project_id") } returns "project-1"
        every { rs.getString("name") } returns "Project"
        every { rs.getString("category") } returns "Category"
        every { rs.getString("description") } returns "Description"
        every { rs.getString("service_content") } returns "Service"
        every { rs.getBigDecimal("price_suggestion") } returns BigDecimal("99.00")
        every { rs.getString("notes") } returns "Notes"
        every { rs.getString("status") } returns "PENDING"
    }

    private fun doctorActor(approvedInstitutions: Set<String> = setOf("institution-1")) = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), approvedInstitutions, setOf("doctor-1")
    )

    private fun legalRepresentativeActor(institutions: Set<String>) = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, institutions, emptySet(), emptySet()
    )

    private fun adminActor() = ManagementActor(
        "admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet()
    )
}
