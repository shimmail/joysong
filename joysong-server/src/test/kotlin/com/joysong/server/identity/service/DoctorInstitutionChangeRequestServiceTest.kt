package com.joysong.server.identity.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDateTime

class DoctorInstitutionChangeRequestServiceTest {
    private val store = mockk<DoctorInstitutionChangeRequestStore>()
    private val service = DoctorInstitutionChangeRequestService(store)

    @Test
    fun `certified doctor submits join request for an unrelated institution`() {
        val actor = doctorActor()
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create("doctor-1", "institution-1", DoctorInstitutionAction.JOIN, "希望加入") } returns
            request(action = DoctorInstitutionAction.JOIN, requestNote = "希望加入")

        val result = service.submit(actor, " institution-1 ", DoctorInstitutionAction.JOIN, " 希望加入 ")

        assertEquals(DoctorInstitutionAction.JOIN, result.action)
        assertEquals(DoctorInstitutionRequestStatus.PENDING, result.status)
        assertEquals("希望加入", result.requestNote)
    }

    @Test
    fun `doctor submits leave request only for an active relationship`() {
        val actor = doctorActor(approvedInstitutions = setOf("institution-1"))
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns true
        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create("doctor-1", "institution-1", DoctorInstitutionAction.LEAVE, "调整安排") } returns
            request(action = DoctorInstitutionAction.LEAVE, requestNote = "调整安排")

        val result = service.submit(actor, "institution-1", DoctorInstitutionAction.LEAVE, "调整安排")

        assertEquals(DoctorInstitutionAction.LEAVE, result.action)
    }

    @Test
    fun `join and leave reject relationship states that do not match the action`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns true

        val joinError = assertThrows<IllegalArgumentException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("医生已加入该机构", joinError.message)

        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        val leaveError = assertThrows<IllegalArgumentException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.LEAVE, "")
        }
        assertEquals("医生尚未加入该机构", leaveError.message)
    }

    @Test
    fun `only a certified doctor acting as self can submit`() {
        val wrongDoctor = doctorActor().copy(doctorId = "doctor-2")

        assertThrows<AccessDeniedException> {
            service.submit(wrongDoctor, "institution-1", DoctorInstitutionAction.JOIN, "")
        }

        every { store.isCertifiedDoctor("doctor-1") } returns false
        val error = assertThrows<AccessDeniedException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("只有已认证医生本人可以提交机构关系申请", error.message)
    }

    @Test
    fun `duplicate pending request and insert race use one stable error`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns true

        val duplicate = assertThrows<IllegalStateException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("该机构已有待处理的关系申请", duplicate.message)

        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create(any(), any(), any(), any()) } throws DuplicateKeyException("pending_key")
        val race = assertThrows<IllegalStateException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("该机构已有待处理的关系申请", race.message)
    }

    @Test
    fun `list is scoped to own requests and managed institutions`() {
        val actor = doctorActor().copy(managedInstitutionIds = setOf("institution-2"))
        val visible = listOf(request(), request(id = "request-2", institutionId = "institution-2"))
        every { store.listVisible("doctor-1", setOf("institution-2")) } returns visible

        val result = service.list(actor)

        assertEquals(listOf("request-1", "request-2"), result.map { it.id })
        verify(exactly = 1) { store.listVisible("doctor-1", setOf("institution-2")) }
    }

    @Test
    fun `request owner withdraws a pending request with a conditional update`() {
        every { store.lock("request-1") } returns request()
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "") } returns true

        val result = service.withdraw(doctorActor(), " request-1 ")

        assertEquals(DoctorInstitutionRequestStatus.WITHDRAWN, result.status)
        verify(exactly = 1) {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "")
        }
    }

    @Test
    fun `another actor cannot withdraw a request`() {
        every { store.lock("request-1") } returns request()

        val error = assertThrows<AccessDeniedException> {
            service.withdraw(legalActor(setOf("institution-1")), "request-1")
        }

        assertEquals("只能撤回本人提交的关系申请", error.message)
        verify(exactly = 0) { store.changeStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `legal representative reviews a managed institution request without relationship side effects`() {
        every { store.lock("request-1") } returns request()
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.APPROVED, "legal-1", "同意") } returns true

        val result = service.review(
            legalActor(setOf("institution-1")),
            "request-1",
            MembershipRequestDecision.APPROVED,
            " 同意 "
        )

        assertEquals(DoctorInstitutionRequestStatus.APPROVED, result.status)
        assertEquals("同意", result.reviewNote)
        verify(exactly = 1) {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.APPROVED, "legal-1", "同意")
        }
    }

    @Test
    fun `review denies cross institution actor and requires rejection reason`() {
        every { store.lock("request-1") } returns request()

        val forbidden = assertThrows<AccessDeniedException> {
            service.review(
                legalActor(setOf("institution-2")),
                "request-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }
        assertEquals("无权审核其他机构的关系申请", forbidden.message)

        val missingReason = assertThrows<IllegalArgumentException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.REJECTED,
                "  "
            )
        }
        assertEquals("驳回时必须填写审核意见", missingReason.message)
    }

    @Test
    fun `only pending requests can be withdrawn or reviewed`() {
        every { store.lock("request-1") } returns request(status = DoctorInstitutionRequestStatus.APPROVED)

        val withdrawError = assertThrows<IllegalArgumentException> {
            service.withdraw(doctorActor(), "request-1")
        }
        assertEquals("只有待审核的关系申请可以撤回", withdrawError.message)

        val reviewError = assertThrows<IllegalArgumentException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.REJECTED,
                "不符合要求"
            )
        }
        assertEquals("只有待审核的关系申请可以审核", reviewError.message)
    }

    @Test
    fun `action and review decision parsers are strict and case insensitive`() {
        assertEquals(DoctorInstitutionAction.JOIN, DoctorInstitutionAction.parse(" join "))
        assertEquals(DoctorInstitutionAction.LEAVE, DoctorInstitutionAction.parse("Leave"))
        assertThrows<IllegalArgumentException> { DoctorInstitutionAction.parse("LINK") }
        val error = assertThrows<IllegalArgumentException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.CHANGES_REQUESTED,
                "需要修改"
            )
        }
        assertEquals("不支持的审核决定", error.message)
    }

    private fun request(
        id: String = "request-1",
        institutionId: String = "institution-1",
        action: DoctorInstitutionAction = DoctorInstitutionAction.JOIN,
        status: DoctorInstitutionRequestStatus = DoctorInstitutionRequestStatus.PENDING,
        requestNote: String = ""
    ) = DoctorInstitutionChangeRequestView(
        id = id,
        doctorId = "doctor-1",
        doctorName = "医生一",
        institutionId = institutionId,
        institutionName = "机构一",
        action = action,
        status = status,
        requestNote = requestNote,
        reviewNote = "",
        submittedBy = "doctor-1",
        reviewedBy = null,
        submittedAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        reviewedAt = null,
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )

    private fun doctorActor(approvedInstitutions: Set<String> = emptySet()) = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = approvedInstitutions,
        manageableDoctorIds = setOf("doctor-1")
    )

    private fun legalActor(institutions: Set<String>) = ManagementActor(
        userId = "legal-1",
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = institutions,
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )
}
