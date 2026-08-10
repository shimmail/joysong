package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.LocalDateTime

class JdbcInstitutionMembershipRequestStoreTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val store = JdbcInstitutionMembershipRequestStore(jdbcTemplate)

    @Test
    fun `doctor lookup includes soft deleted relationship so it can be restored`() {
        val sql = slot<String>()
        every { jdbcTemplate.query(capture(sql), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()

        store.find(MembershipRequestType.DOCTOR, "doctor-1", "institution-1")

        assertFalse(sql.captured.contains("deleted_at IS NULL"))
    }

    @Test
    fun `doctor resubmit restores soft deleted relationship`() {
        val sql = slot<String>()
        every { jdbcTemplate.update(capture(sql), *anyVararg()) } returns 1

        store.resubmit(request(), "重新申请")

        assertTrue(sql.captured.contains("deleted_at = NULL"))
    }

    @Test
    fun `review locks request and rejects lost concurrent update`() {
        val sql = slot<String>()
        every { jdbcTemplate.query(capture(sql), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()
        store.findById(MembershipRequestType.DOCTOR, "request-1")
        assertTrue(sql.captured.contains("FOR UPDATE"))

        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 0
        assertThrows(IllegalStateException::class.java) {
            store.review(request(), "legal-1", MembershipRequestDecision.APPROVED, "")
        }
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
}
