package com.joysong.server.identity.controller

import com.joysong.server.identity.service.DoctorInstitutionAction
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestService
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestView
import com.joysong.server.identity.service.DoctorInstitutionRequestStatus
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestDecision
import com.joysong.server.identity.service.MembershipRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.Authentication
import java.time.LocalDateTime

class InstitutionMembershipRequestControllerTest {
    private val accessService = mockk<ManagementAccessService>()
    private val legacyService = mockk<InstitutionMembershipRequestService>()
    private val doctorService = mockk<DoctorInstitutionChangeRequestService>()
    private val authentication = mockk<Authentication>()
    private val actor = doctorActor()
    private val controller = InstitutionMembershipRequestController(accessService, legacyService, doctorService)

    @Test
    fun `list combines consultant legacy requests and doctor ledger without active doctor relationships`() {
        val consultant = legacyRequest(type = MembershipRequestType.CONSULTANT, id = "consultant-1")
        val activeDoctorRelationship = legacyRequest(type = MembershipRequestType.DOCTOR, id = "relationship-1")
        every { accessService.actor(authentication) } returns actor
        every { legacyService.list(actor) } returns listOf(consultant, activeDoctorRelationship)
        every { doctorService.list(actor) } returns listOf(doctorRequest())

        val response = controller.list(authentication)
        val data = response.data as List<*>

        assertEquals(listOf("doctor-request-1", "consultant-1"), data.map { (it as InstitutionMembershipRequestResponse).id })
        assertEquals(listOf("LEAVE", "JOIN"), data.map { (it as InstitutionMembershipRequestResponse).action })
    }

    @Test
    fun `doctor submit defaults action to join and uses authenticated actor`() {
        every { accessService.actor(authentication) } returns actor
        every {
            doctorService.submit(actor, "institution-1", DoctorInstitutionAction.JOIN, "申请加入")
        } returns doctorRequest(action = DoctorInstitutionAction.JOIN)

        val response = controller.submit(
            authentication,
            SubmitInstitutionMembershipRequest("DOCTOR", "institution-1", "申请加入")
        )

        assertEquals("JOIN", (response.data as InstitutionMembershipRequestResponse).action)
        verify(exactly = 1) {
            doctorService.submit(actor, "institution-1", DoctorInstitutionAction.JOIN, "申请加入")
        }
        verify(exactly = 0) { legacyService.submit(any(), any(), any(), any()) }
    }

    @Test
    fun `doctor submit forwards explicit leave action`() {
        every { accessService.actor(authentication) } returns actor
        every {
            doctorService.submit(actor, "institution-1", DoctorInstitutionAction.LEAVE, "调整安排")
        } returns doctorRequest(action = DoctorInstitutionAction.LEAVE)

        controller.submit(
            authentication,
            SubmitInstitutionMembershipRequest("DOCTOR", "institution-1", "调整安排", "leave")
        )

        verify(exactly = 1) {
            doctorService.submit(actor, "institution-1", DoctorInstitutionAction.LEAVE, "调整安排")
        }
    }

    @Test
    fun `consultant submit stays on legacy service and rejects leave action`() {
        val consultantActor = consultantActor()
        every { accessService.actor(authentication) } returns consultantActor
        every {
            legacyService.submit(consultantActor, MembershipRequestType.CONSULTANT, "institution-1", "申请加入")
        } returns legacyRequest(type = MembershipRequestType.CONSULTANT)

        val response = controller.submit(
            authentication,
            SubmitInstitutionMembershipRequest("CONSULTANT", "institution-1", "申请加入")
        )
        assertEquals("JOIN", (response.data as InstitutionMembershipRequestResponse).action)

        val error = assertThrows<IllegalArgumentException> {
            controller.submit(
                authentication,
                SubmitInstitutionMembershipRequest("CONSULTANT", "institution-1", "", "LEAVE")
            )
        }
        assertEquals("顾问机构申请仅支持加入", error.message)
    }

    @Test
    fun `review reuses membership decision and dispatches by request type`() {
        val legal = legalActor()
        every { accessService.actor(authentication) } returns legal
        every {
            doctorService.review(legal, "doctor-request-1", MembershipRequestDecision.REJECTED, "资料不符")
        } returns doctorRequest(status = DoctorInstitutionRequestStatus.REJECTED)
        every {
            legacyService.review(
                legal,
                MembershipRequestType.CONSULTANT,
                "consultant-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        } returns legacyRequest(type = MembershipRequestType.CONSULTANT, status = "APPROVED")

        controller.review(
            authentication,
            "DOCTOR",
            "doctor-request-1",
            ReviewInstitutionMembershipRequest("REJECTED", "资料不符")
        )
        controller.review(
            authentication,
            "CONSULTANT",
            "consultant-1",
            ReviewInstitutionMembershipRequest("APPROVED")
        )

        verify(exactly = 1) {
            doctorService.review(legal, "doctor-request-1", MembershipRequestDecision.REJECTED, "资料不符")
        }
        verify(exactly = 1) {
            legacyService.review(
                legal,
                MembershipRequestType.CONSULTANT,
                "consultant-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }
    }

    @Test
    fun `withdraw is available only for doctor ledger requests`() {
        every { accessService.actor(authentication) } returns actor
        every { doctorService.withdraw(actor, "doctor-request-1") } returns
            doctorRequest(status = DoctorInstitutionRequestStatus.WITHDRAWN)

        val response = controller.withdraw(authentication, "DOCTOR", "doctor-request-1")
        assertEquals("WITHDRAWN", (response.data as InstitutionMembershipRequestResponse).status)

        val error = assertThrows<IllegalArgumentException> {
            controller.withdraw(authentication, "CONSULTANT", "consultant-1")
        }
        assertEquals("顾问加入申请暂不支持撤回", error.message)
    }

    private fun legacyRequest(
        type: MembershipRequestType,
        id: String = "request-1",
        status: String = "PENDING"
    ) = InstitutionMembershipRequestView(
        id = id,
        requestType = type,
        userId = if (type == MembershipRequestType.DOCTOR) "doctor-1" else "consultant-1",
        institutionId = "institution-1",
        status = status,
        requestNote = "申请加入",
        reviewNote = "",
        createdAt = LocalDateTime.of(2026, 8, 10, 9, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 9, 0)
    )

    private fun doctorRequest(
        action: DoctorInstitutionAction = DoctorInstitutionAction.LEAVE,
        status: DoctorInstitutionRequestStatus = DoctorInstitutionRequestStatus.PENDING
    ) = DoctorInstitutionChangeRequestView(
        id = "doctor-request-1",
        doctorId = "doctor-1",
        doctorName = "医生一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = action,
        status = status,
        requestNote = "",
        reviewNote = "",
        submittedBy = "doctor-1",
        reviewedBy = null,
        submittedAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        reviewedAt = null,
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )

    private fun doctorActor() = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")
    )

    private fun consultantActor() = ManagementActor(
        "consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet()
    )

    private fun legalActor() = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("institution-1"), emptySet(), emptySet()
    )
}
