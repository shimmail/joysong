package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDateTime

class InstitutionMembershipRequestQueryServiceTest {
    @Test
    fun `owned returns both active professional identities full history and nothing managed`() {
        val store = FakeInstitutionMembershipRequestQueryStore(
            listOf(
                request("d-new", MembershipRequestType.DOCTOR, "user-1", "managed-9", 3),
                request("c-old", MembershipRequestType.CONSULTANT, "user-1", "institution-2", 1),
                request("other", MembershipRequestType.DOCTOR, "doctor-2", "managed-1", 2)
            )
        )

        val result = InstitutionMembershipRequestQueryService(store).listOwned(dualActor())

        assertEquals(listOf("d-new", "c-old"), result.map { it.id })
    }

    @Test
    fun `reviewable is limited to managed institutions while admin sees all`() {
        val store = FakeInstitutionMembershipRequestQueryStore(
            listOf(
                request("managed-doctor", MembershipRequestType.DOCTOR, "doctor-2", "managed-1", 3),
                request("managed-consultant", MembershipRequestType.CONSULTANT, "consultant-2", "managed-1", 2),
                request("hidden", MembershipRequestType.DOCTOR, "doctor-3", "managed-2", 1)
            )
        )
        val service = InstitutionMembershipRequestQueryService(store)

        assertEquals(
            listOf("managed-doctor", "managed-consultant"),
            service.listReviewable(legalActor()).map { it.id }
        )
        assertEquals(
            listOf("managed-doctor", "managed-consultant", "hidden"),
            service.listReviewable(adminActor()).map { it.id }
        )
        assertThrows<AccessDeniedException> { service.listReviewable(dualActor()) }
    }

    @Test
    fun `compatibility union deduplicates owned and managed ledger rows`() {
        val request = request("same", MembershipRequestType.DOCTOR, "user-1", "managed-1", 1)
        val service = InstitutionMembershipRequestQueryService(
            FakeInstitutionMembershipRequestQueryStore(listOf(request))
        )

        val rows = service.listCompatibility(
            dualActor().copy(managedInstitutionIds = setOf("managed-1"))
        )

        assertEquals(listOf("same"), rows.map { it.id })
    }

    @Test
    fun `jdbc projections compute current relationship status in one query`() {
        val jdbc = mockk<JdbcTemplate>()
        val sql = slot<String>()
        every {
            jdbc.query(capture(sql), any<RowMapper<InstitutionMembershipRequestView>>(), *anyVararg())
        } returns emptyList()
        val store = JdbcInstitutionMembershipRequestQueryStore(jdbc)

        store.listOwned(MembershipRequestType.DOCTOR, "doctor-1")
        assertTrue(sql.captured.contains("LEFT JOIN doctor_institutions relationship"))
        assertTrue(sql.captured.contains("relationship_status"))

        store.listOwned(MembershipRequestType.CONSULTANT, "consultant-1")
        assertTrue(sql.captured.contains("LEFT JOIN institution_memberships relationship"))
        assertTrue(sql.captured.contains("relationship_status"))
        assertTrue(sql.captured.contains("ORDER BY request.submitted_at DESC, request.id DESC"))
    }

    private fun request(
        id: String,
        type: MembershipRequestType,
        applicantId: String,
        institutionId: String,
        hour: Int
    ) = InstitutionMembershipRequestView(
        id = id,
        requestType = type,
        applicantId = applicantId,
        applicantName = if (type == MembershipRequestType.DOCTOR) "医生" else "顾问",
        institutionId = institutionId,
        institutionName = "机构-$institutionId",
        action = "JOIN",
        status = "PENDING",
        relationshipStatus = "NONE",
        requestNote = "",
        reviewNote = "",
        submittedBy = applicantId,
        reviewedBy = null,
        submittedAt = LocalDateTime.of(2026, 8, 14, hour, 0),
        reviewedAt = null,
        createdAt = LocalDateTime.of(2026, 8, 14, hour, 0),
        updatedAt = LocalDateTime.of(2026, 8, 14, hour, 0)
    )

    private fun dualActor() = ManagementActor(
        "user-1", false, setOf("DOCTOR", "CONSULTANT"), "user-1",
        emptySet(), emptySet(), setOf("user-1")
    )

    private fun legalActor() = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("managed-1"), emptySet(), emptySet()
    )

    private fun adminActor() = ManagementActor(
        "admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet()
    )
}

private class FakeInstitutionMembershipRequestQueryStore(
    private val rows: List<InstitutionMembershipRequestView>
) : InstitutionMembershipRequestQueryStore {
    override fun listOwned(
        requestType: MembershipRequestType,
        applicantId: String
    ): List<InstitutionMembershipRequestView> = rows.filter {
        it.requestType == requestType && it.applicantId == applicantId
    }

    override fun listReviewable(
        requestType: MembershipRequestType,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<InstitutionMembershipRequestView> = rows.filter {
        it.requestType == requestType && (includeAll || it.institutionId in managedInstitutionIds)
    }
}
