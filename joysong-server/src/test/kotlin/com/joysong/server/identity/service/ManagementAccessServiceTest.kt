package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException

class ManagementAccessServiceTest {

    @Test
    fun `active doctor without profile remains an actor for explicit profile not found handling`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "doctor-without-profile")
        } returnsMany listOf(1L, 0L)
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT role_code") },
                String::class.java,
                "doctor-without-profile"
            )
        } returns listOf("DOCTOR")

        val context = ManagementAccessService(jdbcTemplate).contextFor("doctor-without-profile", "USER")

        assertTrue("DOCTOR" in context.activeRoles)
        org.junit.jupiter.api.Assertions.assertNull(context.doctorId)
    }

    @Test
    fun `legal representative capabilities exclude doctor order article and split management`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "legal-1")
        } returns 1L
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT role_code") },
                String::class.java,
                "legal-1"
            )
        } returns listOf("INSTITUTION_LEGAL_REPRESENTATIVE")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT institution_id") },
                String::class.java,
                "legal-1"
            )
        } returns listOf("institution-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT doctor_id") },
                String::class.java,
                "institution-1"
            )
        } returns listOf("doctor-1")

        val context = ManagementAccessService(jdbcTemplate).contextFor("legal-1", "USER")

        assertFalse(context.canManageDoctors)
        assertFalse(context.canManageArticles)
        assertFalse(context.canManageSplitConfigs)
        assertFalse(context.canManageOrders)
        assertFalse(context.canManageInstitutionProjects)
        assertFalse(context.canApplyToInstitutions)
        assertFalse(context.canSubmitPlatformProjectRequests)
        assertFalse(context.canSubmitInstitutionProjectRequests)
        org.junit.jupiter.api.Assertions.assertTrue(context.canReviewInstitutionRequests)
        org.junit.jupiter.api.Assertions.assertTrue(context.canReviewInstitutionProjectRequests)
    }

    @Test
    fun `consultant receives only institution application and affiliation capabilities`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(any<String>(), Long::class.java, "consultant-1")
        } returns 1L
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT role_code") },
                String::class.java,
                "consultant-1"
            )
        } returns listOf("CONSULTANT")

        val context = ManagementAccessService(jdbcTemplate).contextFor("consultant-1", "USER")

        org.junit.jupiter.api.Assertions.assertTrue(context.canApplyToInstitutions)
        org.junit.jupiter.api.Assertions.assertTrue(context.canViewAffiliations)
        assertFalse(context.canManageDoctors)
        assertFalse(context.canManageInstitutionProjects)
        assertFalse(context.canManageOrders)
        assertFalse(context.canManageSplitConfigs)
    }

    @Test
    fun `legal representative cannot manage an institution doctor profile`() {
        val service = ManagementAccessService(mockk(relaxed = true))
        val actor = ManagementActor(
            userId = "legal-1",
            isAdmin = false,
            activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
            doctorId = null,
            managedInstitutionIds = setOf("institution-1"),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )

        assertThrows(AccessDeniedException::class.java) {
            service.requireDoctor(actor, "doctor-1")
        }
    }

    @Test
    fun `professional roles cannot execute platform admin writes`() {
        val service = ManagementAccessService(mockk(relaxed = true))
        val actor = ManagementActor(
            userId = "doctor-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR"),
            doctorId = "doctor-1",
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = setOf("institution-1"),
            manageableDoctorIds = setOf("doctor-1")
        )

        assertThrows(AccessDeniedException::class.java) {
            service.requirePlatformAdmin(actor)
        }
    }
}
