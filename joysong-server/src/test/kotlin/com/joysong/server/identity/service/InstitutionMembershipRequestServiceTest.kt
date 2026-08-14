package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class InstitutionMembershipRequestServiceTest {
    private val doctor = mockk<DoctorInstitutionChangeRequestService>()
    private val consultant = mockk<ConsultantInstitutionChangeRequestService>()
    private val service = InstitutionMembershipRequestService(doctor, consultant)

    @Test
    fun `submit dispatches explicit profession and action to its strict service`() {
        every {
            doctor.submit(doctorActor(), "institution-1", DoctorInstitutionAction.LEAVE, "离开")
        } returns doctorRequest(action = DoctorInstitutionAction.LEAVE)
        every {
            consultant.submit(
                consultantActor(),
                "institution-2",
                ConsultantInstitutionAction.JOIN,
                "加入"
            )
        } returns consultantRequest()

        val doctorResult = service.submit(
            doctorActor(), MembershipRequestType.DOCTOR, "institution-1", InstitutionMembershipAction.LEAVE, "离开"
        )
        val consultantResult = service.submit(
            consultantActor(), MembershipRequestType.CONSULTANT, "institution-2", InstitutionMembershipAction.JOIN, "加入"
        )

        assertEquals("LEAVE", doctorResult.action)
        assertEquals(MembershipRequestType.CONSULTANT, consultantResult.requestType)
        verify(exactly = 1) {
            doctor.submit(doctorActor(), "institution-1", DoctorInstitutionAction.LEAVE, "离开")
        }
        verify(exactly = 1) {
            consultant.submit(
                consultantActor(), "institution-2", ConsultantInstitutionAction.JOIN, "加入"
            )
        }
    }

    @Test
    fun `withdraw dispatches both professions and missing doctor ledger never uses a legacy fallback`() {
        every { doctor.exists("doctor-request") } returns true
        every { doctor.withdraw(doctorActor(), "doctor-request") } returns doctorRequest(
            status = DoctorInstitutionRequestStatus.WITHDRAWN
        )
        every {
            consultant.withdraw(consultantActor(), "consultant-request")
        } returns consultantRequest(status = ConsultantInstitutionRequestStatus.WITHDRAWN)

        assertEquals(
            "WITHDRAWN",
            service.withdraw(doctorActor(), MembershipRequestType.DOCTOR, "doctor-request").status
        )
        assertEquals(
            "WITHDRAWN",
            service.withdraw(
                consultantActor(), MembershipRequestType.CONSULTANT, "consultant-request"
            ).status
        )

        every { doctor.exists("missing") } returns false
        assertThrows<InstitutionMembershipRequestNotFoundException> {
            service.withdraw(doctorActor(), MembershipRequestType.DOCTOR, "missing")
        }
        verify(exactly = 0) { doctor.withdraw(any(), "missing") }
    }

    @Test
    fun `review dispatches both professions and translates lost doctor updates to a typed conflict`() {
        val legal = legalActor()
        every { doctor.exists("doctor-request") } returns true
        every {
            doctor.review(
                legal, "doctor-request", MembershipRequestDecision.REJECTED, "资料不符"
            )
        } returns doctorRequest(status = DoctorInstitutionRequestStatus.REJECTED)
        every {
            consultant.review(
                legal, "consultant-request", MembershipRequestDecision.APPROVED, ""
            )
        } returns consultantRequest(status = ConsultantInstitutionRequestStatus.APPROVED)

        assertEquals(
            "REJECTED",
            service.review(
                legal,
                MembershipRequestType.DOCTOR,
                "doctor-request",
                MembershipRequestDecision.REJECTED,
                "资料不符"
            ).status
        )
        assertEquals(
            "APPROVED",
            service.review(
                legal,
                MembershipRequestType.CONSULTANT,
                "consultant-request",
                MembershipRequestDecision.APPROVED,
                ""
            ).status
        )

        every { doctor.exists("raced") } returns true
        every {
            doctor.review(legal, "raced", MembershipRequestDecision.APPROVED, "")
        } throws IllegalStateException("关系申请已被其他操作处理")
        assertThrows<InstitutionMembershipRequestConflictException> {
            service.review(
                legal,
                MembershipRequestType.DOCTOR,
                "raced",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }
    }

    @Test
    fun `doctor review translates a missing or invalidated institution to typed not found`() {
        val legal = legalActor()
        every { doctor.exists("doctor-request") } returns true
        every {
            doctor.review(
                legal,
                "doctor-request",
                MembershipRequestDecision.APPROVED,
                ""
            )
        } throws IllegalArgumentException("机构不存在、未认证或已删除")

        assertThrows<InstitutionMembershipRequestNotFoundException> {
            service.review(
                legal,
                MembershipRequestType.DOCTOR,
                "doctor-request",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }
    }

    @Test
    fun `doctor invalidation conflict is translated by type without message matching`() {
        val legal = legalActor()
        every { doctor.exists("doctor-request") } returns true
        every {
            doctor.review(
                legal,
                "doctor-request",
                MembershipRequestDecision.APPROVED,
                ""
            )
        } throws DoctorInstitutionRequestConflictException("医生身份已失效")

        val error = assertThrows<InstitutionMembershipRequestConflictException> {
            service.review(
                legal,
                MembershipRequestType.DOCTOR,
                "doctor-request",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }

        assertEquals("医生身份已失效", error.message)
    }

    @Test
    fun `doctor leave relationship race is a typed conflict`() {
        val legal = legalActor()
        every { doctor.exists("doctor-leave") } returns true
        every {
            doctor.review(
                legal,
                "doctor-leave",
                MembershipRequestDecision.APPROVED,
                ""
            )
        } throws IllegalArgumentException("医生已不具备该机构的有效执业关系")

        assertThrows<InstitutionMembershipRequestConflictException> {
            service.review(
                legal,
                MembershipRequestType.DOCTOR,
                "doctor-leave",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }
    }

    private fun doctorRequest(
        action: DoctorInstitutionAction = DoctorInstitutionAction.JOIN,
        status: DoctorInstitutionRequestStatus = DoctorInstitutionRequestStatus.PENDING
    ) = DoctorInstitutionChangeRequestView(
        id = "doctor-request",
        doctorId = "doctor-1",
        doctorName = "医生一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = action,
        status = status,
        requestNote = "申请",
        reviewNote = "",
        submittedBy = "doctor-1",
        reviewedBy = null,
        submittedAt = NOW,
        reviewedAt = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun consultantRequest(
        status: ConsultantInstitutionRequestStatus = ConsultantInstitutionRequestStatus.PENDING
    ) = ConsultantInstitutionChangeRequestView(
        id = "consultant-request",
        consultantId = "consultant-1",
        consultantName = "顾问一",
        institutionId = "institution-2",
        institutionName = "机构二",
        action = ConsultantInstitutionAction.JOIN,
        status = status,
        requestNote = "加入",
        reviewNote = "",
        submittedBy = "consultant-1",
        reviewedBy = null,
        submittedAt = NOW,
        reviewedAt = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun doctorActor() = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")
    )

    private fun consultantActor() = ManagementActor(
        "consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet()
    )

    private fun legalActor() = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("institution-1", "institution-2"), emptySet(), emptySet()
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 14, 10, 0)
    }
}
