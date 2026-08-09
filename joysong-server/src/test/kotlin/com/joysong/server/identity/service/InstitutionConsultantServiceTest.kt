package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class InstitutionConsultantServiceTest {

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
