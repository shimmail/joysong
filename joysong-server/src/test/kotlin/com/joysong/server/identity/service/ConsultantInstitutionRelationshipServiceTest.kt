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
    fun `pair lock always locks user before institution without requiring active status`() {
        val sql = mutableListOf<String>()
        every { jdbc.queryForList(capture(sql), String::class.java, *anyVararg()) } answers {
            if (firstArg<String>().contains("FROM users")) listOf("consultant-1") else listOf("institution-1")
        }

        service.lockPair("consultant-1", "institution-1")

        assertEquals(2, sql.size)
        assertTrue(sql[0].contains("FROM users"))
        assertTrue(sql[0].contains("FOR UPDATE"))
        assertTrue(sql[1].contains("FROM institutions"))
        assertTrue(sql[1].contains("FOR UPDATE"))
        assertFalse(sql[1].contains("is_verified"))
        assertFalse(sql[1].contains("deleted_at"))
    }

    @Test
    fun `active institution validation is a verified nondeleted locking current read`() {
        val sql = slot<String>()
        every { jdbc.queryForList(capture(sql), String::class.java, *anyVararg()) } returns emptyList()

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.requireActiveInstitution("institution-1")
        }

        assertTrue(sql.captured.contains("is_verified = 1"))
        assertTrue(sql.captured.contains("deleted_at IS NULL"))
        assertTrue(sql.captured.contains("FOR UPDATE"))
    }

    @Test
    fun `active consultant role is revalidated with a locking current read`() {
        val sql = slot<String>()
        every { jdbc.queryForList(capture(sql), String::class.java, *anyVararg()) } returns emptyList()

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.requireActiveConsultant("consultant-1")
        }

        assertTrue(sql.captured.contains("FROM user_roles"))
        assertTrue(sql.captured.contains("status = 'ACTIVE'"))
        assertTrue(sql.captured.contains("FOR UPDATE"))
    }

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
    fun `force revoke changes only the target consultant relationship and matching sessions`() {
        val sql = mutableListOf<String>()
        val lockSql = mutableListOf<String>()
        every { jdbc.queryForList(match { it.contains("FROM users") }, String::class.java, *anyVararg()) } returns listOf("consultant-1")
        every {
            jdbc.queryForList(match { it.contains("FROM institutions") }, String::class.java, *anyVararg())
        } answers {
            lockSql += firstArg<String>()
            listOf("institution-1")
        }
        every {
            jdbc.queryForList(match { it.contains("FROM institution_memberships") }, String::class.java, *anyVararg())
        } returns listOf("membership-1")
        every { jdbc.update(capture(sql), *anyVararg()) } returns 1

        service.forceRevoke("consultant-1", "institution-1", "admin-1")

        assertEquals(2, sql.size)
        assertTrue(sql[0].contains("WHERE id = ?"))
        assertTrue(sql[0].contains("status = 'REVOKED'"))
        assertFalse(sql[0].contains("user_roles"))
        assertTrue(sql[1].contains("active_institution_id = ?"))
        assertFalse(sql.any { it.contains("wallet") || it.contains("orders") })
        assertFalse(lockSql.single().contains("is_verified"))
        assertFalse(lockSql.single().contains("deleted_at"))
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
