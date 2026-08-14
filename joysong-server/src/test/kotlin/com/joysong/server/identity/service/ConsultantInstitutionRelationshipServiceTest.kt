package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class ConsultantInstitutionRelationshipServiceTest {
    private val jdbc = mockk<JdbcTemplate>()
    private val service = ConsultantInstitutionRelationshipService(jdbc)

    @Test
    fun `approving join creates one active consultant membership with reviewer metadata`() {
        activeInstitution()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns emptyList()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && !it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns emptyList()
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1

        service.applyApproved("consultant-1", "institution-1", ConsultantInstitutionAction.JOIN, "reviewer-1")

        assertTrue(sql.captured.contains("INSERT INTO institution_memberships"))
        assertTrue(sql.captured.contains("'CONSULTANT'"))
        assertTrue(sql.captured.contains("'APPROVED'"))
        assertTrue(sql.captured.contains("confirmed_by"))
        assertTrue(sql.captured.contains("revoked_at"))
    }

    @Test
    fun `approving join restores the unique consultant binding instead of inserting another`() {
        activeInstitution()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns emptyList()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && !it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns listOf("membership-1")
        val sql = slot<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1

        service.applyApproved("consultant-1", "institution-1", ConsultantInstitutionAction.JOIN, "reviewer-1")

        assertTrue(sql.captured.contains("UPDATE institution_memberships"))
        assertTrue(sql.captured.contains("status = 'APPROVED'"))
        assertTrue(sql.captured.contains("revoked_at = NULL"))
        assertTrue(sql.captured.contains("confirmed_by = ?"))
        assertFalse(sql.captured.contains("INSERT"))
    }

    @Test
    fun `approving leave revokes only the target membership and clears only matching consultant sessions`() {
        activeInstitution()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns listOf("membership-1")
        val sql = mutableListOf<String>()
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1

        service.applyApproved("consultant-1", "institution-1", ConsultantInstitutionAction.LEAVE, "reviewer-1")

        assertEquals(2, sql.size)
        val membershipSql = sql.first { it.contains("UPDATE institution_memberships") }
        assertTrue(membershipSql.contains("WHERE id = ?"))
        assertTrue(membershipSql.contains("status = 'APPROVED'"))
        assertTrue(membershipSql.contains("revoked_at IS NULL"))
        assertTrue(membershipSql.contains("status = 'REVOKED'"))

        val sessionSql = sql.first { it.contains("UPDATE auth_sessions") }
        assertTrue(sessionSql.contains("active_role = 'CONSULTANT'"))
        assertTrue(sessionSql.contains("active_institution_id = ?"))
        assertTrue(sessionSql.contains("SET active_role = 'USER', active_institution_id = NULL"))
    }

    @Test
    fun `review validation rejects stale join and leave relationship state`() {
        activeInstitution()
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns listOf("membership-1")

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.validateForReview("consultant-1", "institution-1", ConsultantInstitutionAction.JOIN)
        }

        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") && it.contains("status = 'APPROVED'") }, String::class.java, *anyVararg())
        } returns emptyList()
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.validateForReview("consultant-1", "institution-1", ConsultantInstitutionAction.LEAVE)
        }
    }

    private fun activeInstitution() {
        every {
            jdbc.queryForList(match { it.contains("FROM institutions") }, String::class.java, *anyVararg())
        } returns listOf("institution-1")
    }
}
