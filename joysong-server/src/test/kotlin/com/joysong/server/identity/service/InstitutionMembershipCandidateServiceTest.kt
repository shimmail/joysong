package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException

class InstitutionMembershipCandidateServiceTest {
    @Test
    fun `doctor query uses explicit identity escapes LIKE and caps pages`() {
        val store = RecordingCandidateStore(
            (1..101).map { InstitutionMembershipCandidateView("id-$it", "机构-$it") }
        )
        val page = InstitutionMembershipCandidateService(store).list(
            doctorActor(),
            MembershipRequestType.DOCTOR,
            InstitutionMembershipAction.JOIN,
            " 50%_\\中心 ",
            offset = 7,
            limit = 500
        )

        assertEquals("doctor-1", store.applicantId)
        assertEquals("%50\\%\\_\\\\中心%", store.queryPattern)
        assertEquals(101, store.limit)
        assertEquals(100, page.items.size)
        assertEquals(100, page.limit)
        assertEquals(7, page.offset)
        assertTrue(page.hasMore)
    }

    @Test
    fun `consultant leave uses consultant identity and exact final page`() {
        val store = RecordingCandidateStore(
            listOf(InstitutionMembershipCandidateView("institution-1", "机构一"))
        )
        val page = InstitutionMembershipCandidateService(store).list(
            consultantActor(),
            MembershipRequestType.CONSULTANT,
            InstitutionMembershipAction.LEAVE,
            "",
            offset = 0,
            limit = 20
        )

        assertEquals("consultant-1", store.applicantId)
        assertEquals(null, store.queryPattern)
        assertEquals(listOf("机构一"), page.items.map { it.name })
        assertFalse(page.hasMore)
    }

    @Test
    fun `candidate identity and pagination validation reject invalid access`() {
        val service = InstitutionMembershipCandidateService(RecordingCandidateStore(emptyList()))

        assertThrows<AccessDeniedException> {
            service.list(
                consultantActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
                "", 0, 20
            )
        }
        assertThrows<IllegalArgumentException> {
            service.list(
                doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
                "", -1, 20
            )
        }
        assertThrows<IllegalArgumentException> {
            service.list(
                doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
                "", 0, 0
            )
        }
    }

    @Test
    fun `jdbc JOIN and LEAVE queries enforce eligibility pending exclusion and stable ordering`() {
        val jdbc = mockk<JdbcTemplate>()
        val sql = slot<String>()
        every {
            jdbc.query(capture(sql), any<RowMapper<InstitutionMembershipCandidateView>>(), *anyVararg())
        } returns emptyList()
        val store = JdbcInstitutionMembershipCandidateStore(jdbc)

        store.search(
            MembershipRequestType.DOCTOR,
            "doctor-1",
            InstitutionMembershipAction.JOIN,
            null,
            0,
            21
        )
        assertTrue(sql.captured.contains("institution.is_verified = 1"))
        assertTrue(sql.captured.contains("institution.deleted_at IS NULL"))
        assertTrue(sql.captured.contains("NOT EXISTS"))
        assertTrue(sql.captured.contains("doctor_institutions relationship"))
        assertTrue(sql.captured.contains("doctor_institution_change_requests pending"))
        assertTrue(sql.captured.contains("ORDER BY institution.name ASC, institution.id ASC"))

        store.search(
            MembershipRequestType.CONSULTANT,
            "consultant-1",
            InstitutionMembershipAction.LEAVE,
            "%机构%",
            0,
            21
        )
        assertTrue(sql.captured.contains("JOIN institution_memberships relationship"))
        assertTrue(sql.captured.contains("relationship.status = 'APPROVED'"))
        assertTrue(sql.captured.contains("relationship.revoked_at IS NULL"))
        assertTrue(sql.captured.contains("consultant_institution_change_requests pending"))
        assertTrue(sql.captured.contains("LIKE LOWER(?) ESCAPE"))
    }

    private fun doctorActor() = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")
    )

    private fun consultantActor() = ManagementActor(
        "consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet()
    )
}

private class RecordingCandidateStore(
    private val rows: List<InstitutionMembershipCandidateView>
) : InstitutionMembershipCandidateStore {
    var applicantId: String? = null
    var queryPattern: String? = null
    var limit: Int? = null

    override fun search(
        requestType: MembershipRequestType,
        applicantId: String,
        action: InstitutionMembershipAction,
        queryPattern: String?,
        offset: Int,
        limit: Int
    ): List<InstitutionMembershipCandidateView> {
        this.applicantId = applicantId
        this.queryPattern = queryPattern
        this.limit = limit
        return rows.take(limit)
    }
}
