package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.LocalDateTime

class JdbcInstitutionMembershipRequestStoreTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val legacyStore = JdbcInstitutionMembershipRequestStore(jdbcTemplate)
    private val consultantStore = JdbcConsultantInstitutionChangeRequestStore(jdbcTemplate)

    @Test
    fun `doctor lookup includes soft deleted relationship so it can be restored`() {
        val sql = slot<String>()
        every { jdbcTemplate.query(capture(sql), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()

        legacyStore.find(MembershipRequestType.DOCTOR, "doctor-1", "institution-1")

        assertFalse(sql.captured.contains("deleted_at IS NULL"))
    }

    @Test
    fun `doctor resubmit restores soft deleted relationship`() {
        val sql = slot<String>()
        every { jdbcTemplate.update(capture(sql), *anyVararg()) } returns 1

        legacyStore.resubmit(request(), "重新申请")

        assertTrue(sql.captured.contains("deleted_at = NULL"))
    }

    @Test
    fun `review locks request and rejects lost concurrent update`() {
        val sql = slot<String>()
        every { jdbcTemplate.query(capture(sql), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()
        legacyStore.findById(MembershipRequestType.DOCTOR, "request-1")
        assertTrue(sql.captured.contains("FOR UPDATE"))

        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 0
        assertThrows(IllegalStateException::class.java) {
            legacyStore.review(request(), "legal-1", MembershipRequestDecision.APPROVED, "")
        }
    }

    @Test
    fun `consultant create appends a pending ledger row and returns display names with full audit projection`() {
        val updateSql = slot<String>()
        val querySql = slot<String>()
        val joined = consultantRequest()
        every { jdbcTemplate.update(capture(updateSql), *anyVararg()) } returns 1
        every { jdbcTemplate.query(capture(querySql), any<RowMapper<Any>>(), *anyVararg()) } returns listOf(joined)

        val created = consultantStore.create(
            "consultant-1",
            "institution-1",
            ConsultantInstitutionAction.JOIN,
            "加入"
        )

        assertEquals("机构一", created.institutionName)
        assertTrue(updateSql.captured.contains("INSERT INTO consultant_institution_change_requests"))
        assertFalse(updateSql.captured.contains("INSERT INTO institution_memberships"))
        assertTrue(querySql.captured.contains("JOIN users"))
        assertTrue(querySql.captured.contains("JOIN institutions"))
        assertTrue(querySql.captured.contains("submitted_at"))
        assertTrue(querySql.captured.contains("reviewed_at"))
    }

    @Test
    fun `consultant ledger lock and conditional close expose lost concurrent update`() {
        val querySql = slot<String>()
        every { jdbcTemplate.query(capture(querySql), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()
        consultantStore.lock("request-1")
        assertTrue(querySql.captured.contains("FOR UPDATE"))

        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 0
        assertFalse(
            consultantStore.changeStatus(
                "request-1",
                ConsultantInstitutionRequestStatus.APPROVED,
                "reviewer-1",
                "approved"
            )
        )
    }

    @Test
    fun `consultant active relationship lookup uses only approved non-revoked projection`() {
        val sql = slot<String>()
        every { jdbcTemplate.queryForObject(capture(sql), Long::class.java, *anyVararg()) } returns 1L

        assertTrue(consultantStore.hasActiveRelationship("consultant-1", "institution-1"))

        assertTrue(sql.captured.contains("member_role = 'CONSULTANT'"))
        assertTrue(sql.captured.contains("status = 'APPROVED'"))
        assertTrue(sql.captured.contains("revoked_at IS NULL"))
    }

    private fun request() = InstitutionMembershipRequestView(
        id = "request-1",
        requestType = MembershipRequestType.DOCTOR,
        userId = "doctor-1",
        institutionId = "institution-1",
        status = "PENDING",
        requestNote = "申请",
        reviewNote = "",
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )

    private fun consultantRequest() = ConsultantInstitutionChangeRequestView(
        id = "request-1",
        consultantId = "consultant-1",
        consultantName = "顾问一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = ConsultantInstitutionAction.JOIN,
        status = ConsultantInstitutionRequestStatus.PENDING,
        requestNote = "加入",
        reviewNote = "",
        submittedBy = "consultant-1",
        reviewedBy = null,
        submittedAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        reviewedAt = null,
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )
}
