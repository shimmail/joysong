package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class InstitutionConsultantServiceTest {

    @Test
    fun `consultable institution ids use one set query with picker eligibility`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val service = InstitutionConsultantService(jdbcTemplate)
        val idsSql = slot<String>()
        val pickerSql = slot<String>()

        every { jdbcTemplate.queryForList(capture(idsSql), String::class.java) } returns
            listOf("institution-2", "institution-1")
        every {
            jdbcTemplate.query(
                capture(pickerSql),
                any<RowMapper<InstitutionConsultant>>(),
                "institution-1"
            )
        } returns emptyList()

        assertEquals(setOf("institution-2", "institution-1"), service.listConsultableInstitutionIds())
        service.listApprovedConsultants("institution-1")

        listOf(
            "im.member_role = 'CONSULTANT'",
            "im.status = 'APPROVED'",
            "im.revoked_at IS NULL",
            "u.deleted_at IS NULL",
            "i.deleted_at IS NULL",
            "u.nickname IS NOT NULL",
            "TRIM(u.nickname) <> ''"
        ).forEach { predicate ->
            assertTrue(idsSql.captured.contains(predicate))
            assertTrue(pickerSql.captured.contains(predicate))
        }
        assertTrue(idsSql.captured.contains("i.is_verified = TRUE"))
        verify(exactly = 1) { jdbcTemplate.queryForList(any<String>(), String::class.java) }
    }

    @Test
    fun `requireApprovedConsultant rejects absent approved consultant with medical beauty terminology`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val service = InstitutionConsultantService(jdbcTemplate)
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<InstitutionConsultant>>(), "institution-1")
        } returns emptyList()

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.requireApprovedConsultant("institution-1", "consultant-1")
        }

        assertEquals("所选医美顾问未加入该机构或尚未确认", error.message)
    }
}
