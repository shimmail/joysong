package com.joysong.server.identity.controller

import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestDecision
import com.joysong.server.identity.service.MembershipRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.time.LocalDateTime

class InstitutionMembershipRequestControllerTest {
    private val access = mockk<ManagementAccessService>()
    private val mutations = mockk<InstitutionMembershipRequestService>()
    private val queries = mockk<InstitutionMembershipRequestQueryService>()
    private val authentication = mockk<Authentication>()
    private val controller = InstitutionMembershipRequestController(access, mutations, queries)

    @Test
    fun `owned and reviewable use explicit scopes and normalized fields`() {
        val actor = dualIdentityActor()
        val legal = legalActor()
        every { access.actor(authentication) } returnsMany listOf(actor, legal)
        every { queries.listOwned(actor) } returns listOf(request("doctor-1", MembershipRequestType.DOCTOR))
        every { queries.listReviewable(legal) } returns listOf(
            request("consultant-1", MembershipRequestType.CONSULTANT)
        )

        val owned = controller.owned(authentication).data!!.single()
        val reviewable = controller.reviewable(authentication).data!!.single()

        assertEquals("doctor-1", owned.applicantId)
        assertEquals("医生一", owned.applicantName)
        assertEquals("APPROVED", owned.relationshipStatus)
        assertEquals("consultant-1", reviewable.applicantId)
        assertEquals("机构一", reviewable.institutionName)
        verify(exactly = 1) { queries.listOwned(actor) }
        verify(exactly = 1) { queries.listReviewable(legal) }
    }

    @Test
    fun `root GET preserves userId alias only in its legacy representation`() {
        val actor = dualIdentityActor()
        every { access.actor(authentication) } returns actor
        every { queries.listCompatibility(actor) } returns listOf(
            request("doctor-1", MembershipRequestType.DOCTOR)
        )
        every { queries.listOwned(actor) } returns listOf(
            request("doctor-1", MembershipRequestType.DOCTOR)
        )

        val legacy = controller.list(authentication).data!!.single()
        val normalized = controller.owned(authentication).data!!.single()

        assertEquals(legacy.applicantId, legacy.userId)
        assertEquals("doctor-1", legacy.userId)
        assertNull(normalized::class.members.firstOrNull { it.name == "userId" })
    }

    @Test
    fun `submit withdraw and review forward explicit typed requests to the unified dispatcher`() {
        val actor = dualIdentityActor()
        val legal = legalActor()
        every { access.actor(authentication) } returnsMany listOf(actor, actor, legal)
        every {
            mutations.submit(
                actor,
                MembershipRequestType.CONSULTANT,
                "institution-1",
                InstitutionMembershipAction.LEAVE,
                "离开"
            )
        } returns request("consultant-1", MembershipRequestType.CONSULTANT, action = "LEAVE")
        every {
            mutations.withdraw(actor, MembershipRequestType.DOCTOR, "doctor-request")
        } returns request("doctor-1", MembershipRequestType.DOCTOR, status = "WITHDRAWN")
        every {
            mutations.review(
                legal,
                MembershipRequestType.CONSULTANT,
                "consultant-request",
                MembershipRequestDecision.REJECTED,
                "资料不符"
            )
        } returns request(
            "consultant-1", MembershipRequestType.CONSULTANT, status = "REJECTED"
        )

        assertEquals(
            "LEAVE",
            controller.submit(
                authentication,
                SubmitInstitutionMembershipRequest(
                    requestType = "CONSULTANT",
                    institutionId = "institution-1",
                    action = "LEAVE",
                    requestNote = "离开"
                )
            ).data!!.action
        )
        assertEquals(
            "WITHDRAWN",
            controller.withdraw(authentication, "DOCTOR", "doctor-request").data!!.status
        )
        assertEquals(
            "REJECTED",
            controller.review(
                authentication,
                "CONSULTANT",
                "consultant-request",
                ReviewInstitutionMembershipRequest("REJECTED", "资料不符")
            ).data!!.status
        )
    }

    private fun request(
        applicantId: String,
        type: MembershipRequestType,
        action: String = "JOIN",
        status: String = "PENDING"
    ) = InstitutionMembershipRequestView(
        id = "${type.name.lowercase()}-request",
        requestType = type,
        applicantId = applicantId,
        applicantName = if (type == MembershipRequestType.DOCTOR) "医生一" else "顾问一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = action,
        status = status,
        relationshipStatus = "APPROVED",
        requestNote = "申请",
        reviewNote = "",
        submittedBy = applicantId,
        reviewedBy = null,
        submittedAt = NOW,
        reviewedAt = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun dualIdentityActor() = ManagementActor(
        "doctor-1", false, setOf("DOCTOR", "CONSULTANT"), "doctor-1",
        emptySet(), emptySet(), setOf("doctor-1")
    )

    private fun legalActor() = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("institution-1"), emptySet(), emptySet()
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 14, 10, 0)
    }
}
