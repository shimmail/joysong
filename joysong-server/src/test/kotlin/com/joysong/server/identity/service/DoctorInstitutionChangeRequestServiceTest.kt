package com.joysong.server.identity.service

import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.notification.service.ProfessionalApplicantRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDateTime
import java.sql.SQLIntegrityConstraintViolationException

class DoctorInstitutionChangeRequestServiceTest {
    private val store = mockk<DoctorInstitutionChangeRequestStore>()
    private val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
    private val reviewAuthority = mockk<InstitutionRelationshipReviewAuthorityOperations>(relaxed = true)
    private val businessNotifications = mockk<BusinessNotificationService>(relaxed = true)
    private val service = DoctorInstitutionChangeRequestService(
        store,
        relationshipService,
        reviewAuthority,
        businessNotifications
    )

    init {
        every { store.isActiveInstitution(any()) } returns true
        every { relationshipService.hasActiveCertifiedDoctorForUpdate(any()) } returns true
    }

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
        verify(exactly = 1) {
            businessNotifications.professionalApplicationSubmitted(
                "institution-1",
                ProfessionalApplicantRole.DOCTOR,
                "request-1"
            )
        }
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
    fun `submit revalidates the current doctor role under the relationship transaction lock`() {
        every { relationshipService.hasActiveCertifiedDoctorForUpdate("doctor-1") } returns false
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create("doctor-1", "institution-1", DoctorInstitutionAction.JOIN, "") } returns request()
        every {
            relationshipService.validateForReview(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.JOIN
            )
        } throws DoctorInstitutionRequestConflictException("医生身份已失效")

        val error = assertThrows<AccessDeniedException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }

        assertEquals("只有已认证医生本人可以提交机构关系申请", error.message)
        verify(exactly = 0) { store.create(any(), any(), any(), any()) }
        verify(exactly = 1) { relationshipService.lockPair(listOf("doctor-1"), "institution-1") }
    }

    @Test
    fun `join and leave reject stale relationship states with typed conflicts`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns true

        val joinError = assertThrows<DoctorInstitutionRequestConflictException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("医生已加入该机构", joinError.message)

        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        val leaveError = assertThrows<DoctorInstitutionRequestConflictException> {
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

        every { relationshipService.hasActiveCertifiedDoctorForUpdate("doctor-1") } returns false
        every {
            relationshipService.validateForReview(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.JOIN
            )
        } throws DoctorInstitutionRequestConflictException("医生身份已失效")
        val error = assertThrows<AccessDeniedException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("只有已认证医生本人可以提交机构关系申请", error.message)
    }

    @Test
    fun `join submit rejects a missing deleted or unverified institution before insert`() {
        every {
            relationshipService.validateForReview(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.JOIN
            )
        } throws DoctorInstitutionRequestNotFoundException("机构不存在、未认证或已删除")

        val error = assertThrows<DoctorInstitutionRequestNotFoundException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }

        assertEquals("机构不存在、未认证或已删除", error.message)
        verify(exactly = 0) { store.create(any(), any(), any(), any()) }
    }

    @Test
    fun `submit trims request note and rejects more than one thousand characters before insert`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create("doctor-1", "institution-1", DoctorInstitutionAction.JOIN, "x".repeat(1000)) } returns
            request(action = DoctorInstitutionAction.JOIN, requestNote = "x".repeat(1000))

        val accepted = service.submit(
            doctorActor(),
            "institution-1",
            DoctorInstitutionAction.JOIN,
            "  ${"x".repeat(1000)}  "
        )
        assertEquals(1000, accepted.requestNote.length)

        val error = assertThrows<IllegalArgumentException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "x".repeat(1001))
        }
        assertEquals("申请说明不能超过1000个字符", error.message)
        verify(exactly = 1) { store.create(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate pending request and insert race use one stable error`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns true

        val duplicate = assertThrows<DoctorInstitutionRequestConflictException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("该机构已有待处理的关系申请", duplicate.message)

        every { store.hasPending("doctor-1", "institution-1") } returns false
        every { store.create(any(), any(), any(), any()) } throws pendingConflict()
        val race = assertThrows<DoctorInstitutionRequestConflictException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }
        assertEquals("该机构已有待处理的关系申请", race.message)
        verify(exactly = 0) {
            businessNotifications.professionalApplicationSubmitted(any(), any(), any())
        }
    }

    @Test
    fun `foreign key duplicate exception is not masked as pending conflict`() {
        every { store.isCertifiedDoctor("doctor-1") } returns true
        every { store.hasActiveRelationship("doctor-1", "institution-1") } returns false
        every { store.hasPending("doctor-1", "institution-1") } returns false
        val databaseError = DuplicateKeyException(
            "foreign key failure",
            SQLIntegrityConstraintViolationException("Cannot add child row", "23000", 1452)
        )
        every { store.create(any(), any(), any(), any()) } throws databaseError

        val thrown = assertThrows<DuplicateKeyException> {
            service.submit(doctorActor(), "institution-1", DoctorInstitutionAction.JOIN, "")
        }

        assertTrue(thrown === databaseError)
    }

    @Test
    fun `list is scoped to own requests and managed institutions`() {
        val actor = doctorActor().copy(managedInstitutionIds = setOf("institution-2"))
        val visible = listOf(request(), request(id = "request-2", institutionId = "institution-2"))
        every { store.listVisible("doctor-1", setOf("institution-2"), false) } returns visible

        val result = service.list(actor)

        assertEquals(listOf("request-1", "request-2"), result.map { it.id })
        verify(exactly = 1) { store.listVisible("doctor-1", setOf("institution-2"), false) }
    }

    @Test
    fun `request owner can still list history after the doctor role was revoked`() {
        val staleActor = doctorActor().copy(activeRoles = emptySet(), doctorId = null)
        every { store.listVisible("doctor-1", emptySet(), false) } returns listOf(request())

        val result = service.list(staleActor)

        assertEquals(listOf("request-1"), result.map { it.id })
    }

    @Test
    fun `admin list requests all doctor relationship changes`() {
        every { store.listVisible(null, emptySet(), true) } returns listOf(request())

        val result = service.list(adminActor())

        assertEquals(listOf("request-1"), result.map { it.id })
        verify(exactly = 1) { store.listVisible(null, emptySet(), true) }
    }

    @Test
    fun `request existence is delegated for rolling legacy routing`() {
        every { store.exists("request-1") } returns true

        assertTrue(service.exists(" request-1 "))
    }

    @Test
    fun `request owner withdraws a pending request with a conditional update`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "") } returns true

        val result = service.withdraw(doctorActor(), " request-1 ")

        assertEquals(DoctorInstitutionRequestStatus.WITHDRAWN, result.status)
        verify(exactly = 1) {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "")
        }
        verify(exactly = 1) {
            businessNotifications.professionalApplicationWithdrawn(
                "institution-1",
                ProfessionalApplicantRole.DOCTOR,
                "request-1"
            )
        }
    }

    @Test
    fun `request owner can withdraw after the doctor role was revoked`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "")
        } returns true
        val staleActor = doctorActor().copy(activeRoles = emptySet(), doctorId = null)

        val result = service.withdraw(staleActor, "request-1")

        assertEquals(DoctorInstitutionRequestStatus.WITHDRAWN, result.status)
    }

    @Test
    fun `another actor cannot withdraw a request`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()

        val error = assertThrows<AccessDeniedException> {
            service.withdraw(legalActor(setOf("institution-1")), "request-1")
        }

        assertEquals("只能撤回本人提交的关系申请", error.message)
        verify(exactly = 0) { store.changeStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `join approval applies relationship before closing request`() {
        every { store.find("request-1") } returns request()
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
            relationshipService.applyApprovedLocked(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.JOIN,
                "legal-1"
            )
        }
        verify(exactly = 1) {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.APPROVED, "legal-1", "同意")
        }
        verify(exactly = 1) {
            businessNotifications.professionalApplicationApproved(
                "doctor-1",
                ProfessionalApplicantRole.DOCTOR,
                "request-1"
            )
        }
    }

    @Test
    fun `leave approval cleans relationship before closing request`() {
        every { store.find("request-1") } returns request(action = DoctorInstitutionAction.LEAVE)
        every { store.lock("request-1") } returns request(action = DoctorInstitutionAction.LEAVE)
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.APPROVED, "legal-1", "") } returns true

        service.review(
            legalActor(setOf("institution-1")),
            "request-1",
            MembershipRequestDecision.APPROVED,
            ""
        )

        verify(exactly = 1) {
            relationshipService.applyApprovedLocked(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.LEAVE,
                "legal-1"
            )
        }
        verify(exactly = 1) {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.APPROVED, "legal-1", "")
        }
    }

    @Test
    fun `failed relationship effect leaves request pending for transaction rollback`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every {
            relationshipService.applyApprovedLocked(any(), any(), any(), any())
        } throws IllegalStateException("关系已变化")

        assertThrows<IllegalStateException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }

        verify(exactly = 0) { store.changeStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `review denies cross institution actor and requires rejection reason`() {
        every { store.find("request-1") } returns request()
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
    fun `rejection revalidates doctor institution and join relationship before closing request`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every {
            relationshipService.validateForReview(
                "doctor-1",
                "institution-1",
                DoctorInstitutionAction.JOIN
            )
        } throws DoctorInstitutionRequestConflictException("医生身份已失效")

        val error = assertThrows<DoctorInstitutionRequestConflictException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.REJECTED,
                "暂不通过"
            )
        }

        assertEquals("医生身份已失效", error.message)
        verify(exactly = 0) { store.changeStatus(any(), any(), any(), any()) }
        verify(exactly = 0) { businessNotifications.professionalApplicationRejected(any(), any(), any(), any()) }
    }

    @Test
    fun `rejection notifies the doctor with the trimmed review note only after status change`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.REJECTED, "legal-1", "暂不通过") } returns true

        service.review(
            legalActor(setOf("institution-1")),
            "request-1",
            MembershipRequestDecision.REJECTED,
            " 暂不通过 "
        )

        verify(exactly = 1) {
            businessNotifications.professionalApplicationRejected(
                "doctor-1",
                ProfessionalApplicantRole.DOCTOR,
                "request-1",
                "暂不通过"
            )
        }
    }

    @Test
    fun `failed doctor status transition emits no professional notification`() {
        every { store.find("request-1") } returns request()
        every { store.lock("request-1") } returns request()
        every { store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "") } returns false

        assertThrows<DoctorInstitutionRequestConflictException> {
            service.withdraw(doctorActor(), "request-1")
        }

        verify(exactly = 0) {
            businessNotifications.professionalApplicationWithdrawn(any(), any(), any())
        }
    }

    @Test
    fun `withdraw peeks then pair locks before relocking the request`() {
        val pending = request()
        every { store.find("request-1") } returns pending
        every { store.lock("request-1") } returns pending
        every {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.WITHDRAWN, "doctor-1", "")
        } returns true

        service.withdraw(doctorActor(), "request-1")

        io.mockk.verifyOrder {
            store.find("request-1")
            relationshipService.lockPair(listOf("doctor-1"), "institution-1")
            store.lock("request-1")
        }
    }

    @Test
    fun `only pending requests can be withdrawn or reviewed`() {
        every { store.lock("request-1") } returns request(status = DoctorInstitutionRequestStatus.APPROVED)
        every { store.find("request-1") } returns request(status = DoctorInstitutionRequestStatus.APPROVED)

        val withdrawError = assertThrows<DoctorInstitutionRequestConflictException> {
            service.withdraw(doctorActor(), "request-1")
        }
        assertEquals("只有待审核的关系申请可以撤回", withdrawError.message)

        val reviewError = assertThrows<DoctorInstitutionRequestConflictException> {
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
            MembershipRequestDecision.parse("CHANGES_REQUESTED")
        }
        assertEquals("不支持的审核决定", error.message)
    }

    @Test
    fun `missing request is reported by a typed not found exception`() {
        every { store.find("missing") } returns null

        val error = assertThrows<DoctorInstitutionRequestNotFoundException> {
            service.review(adminActor(), "missing", MembershipRequestDecision.APPROVED, "")
        }

        assertEquals("医生机构关系申请不存在", error.message)
    }

    @Test
    fun `review uses sorted user and institution locks then current authority before request lock`() {
        val peek = request()
        every { store.find("request-1") } returns peek
        every { store.lock("request-1") } returns peek
        every {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.REJECTED, "legal-1", "不通过")
        } returns true
        val actor = legalActor(setOf("institution-1"))

        service.review(actor, "request-1", MembershipRequestDecision.REJECTED, "不通过")

        io.mockk.verifyOrder {
            store.find("request-1")
            relationshipService.lockPair(listOf("doctor-1", "legal-1"), "institution-1")
            relationshipService.validateForReview("doctor-1", "institution-1", DoctorInstitutionAction.JOIN)
            reviewAuthority.requireCurrentAuthority(actor, "institution-1")
            store.lock("request-1")
        }
    }

    @Test
    fun `stale legal representative is denied by current authority before request mutation`() {
        val peek = request()
        every { store.find("request-1") } returns peek
        every {
            reviewAuthority.requireCurrentAuthority(any(), "institution-1")
        } throws AccessDeniedException("当前法人权限已失效")

        assertThrows<AccessDeniedException> {
            service.review(
                legalActor(setOf("institution-1")),
                "request-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }

        verify(exactly = 0) { store.lock(any()) }
        verify(exactly = 0) { store.changeStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `platform admin review still passes through the common authority check`() {
        val peek = request()
        every { store.find("request-1") } returns peek
        every { store.lock("request-1") } returns peek
        every {
            store.changeStatus("request-1", DoctorInstitutionRequestStatus.REJECTED, "admin-1", "不通过")
        } returns true
        val actor = adminActor()

        val result = service.review(actor, "request-1", MembershipRequestDecision.REJECTED, "不通过")

        assertEquals(DoctorInstitutionRequestStatus.REJECTED, result.status)
        verify(exactly = 1) { reviewAuthority.requireCurrentAuthority(actor, "institution-1") }
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

    private fun adminActor() = ManagementActor(
        userId = "admin-1",
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun pendingConflict() = DuplicateKeyException(
        "duplicate pending",
        SQLIntegrityConstraintViolationException(
            "Duplicate entry for key 'uk_doctor_institution_change_requests_pending'",
            "23000",
            1062
        )
    )
}

class DoctorInstitutionRelationshipServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val service = DoctorInstitutionRelationshipService(jdbcTemplate)

    @Test
    fun `active relationship lock uses for update and rejects a missing row`() {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("doctor_institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns emptyList()

        assertThrows<AccessDeniedException> {
            service.requireActiveRelationshipForUpdate("doctor-1", "institution-1")
        }
    }

    @Test
    fun `pair coordination locks sorted users before the institution`() {
        val lockedRows = mutableListOf<String>()
        every {
            jdbcTemplate.queryForList(any<String>(), String::class.java, *anyVararg())
        } answers {
            val sql = firstArg<String>()
            val id = thirdArg<Array<*>>().single() as String
            lockedRows += if (sql.contains("FROM users")) "user:$id" else "institution:$id"
            listOf(id)
        }

        service.lockPair(listOf("reviewer-2", "doctor-1", "doctor-1"), "institution-1")

        assertEquals(
            listOf("user:doctor-1", "user:reviewer-2", "institution:institution-1"),
            lockedRows
        )
    }

    @Test
    fun `join approval reports an invalidated doctor as a typed conflict`() {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctors d") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns emptyList()

        val error = assertThrows<DoctorInstitutionRequestConflictException> {
            service.approveJoin("doctor-1", "institution-1", "legal-1")
        }

        assertEquals("医生身份已失效", error.message)
        verify(exactly = 0) {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") },
                String::class.java,
                any()
            )
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `review validation rejects an unverified institution with typed not found`() {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctors d") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("doctor-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") && it.contains("is_verified = 1") },
                String::class.java,
                "institution-1"
            )
        } returns emptyList()

        val error = assertThrows<DoctorInstitutionRequestNotFoundException> {
            service.validateForReview("doctor-1", "institution-1", DoctorInstitutionAction.JOIN)
        }

        assertEquals("机构不存在、未认证或已删除", error.message)
    }

    @Test
    fun `review validation rejects join when the relationship became active`() {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctors d") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("doctor-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") && it.contains("is_verified = 1") },
                String::class.java,
                "institution-1"
            )
        } returns listOf("机构一")
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("FROM doctor_institutions") &&
                        it.contains("status = 'APPROVED'") &&
                        it.contains("FOR UPDATE")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns listOf("relationship-1")

        val error = assertThrows<DoctorInstitutionRequestConflictException> {
            service.validateForReview("doctor-1", "institution-1", DoctorInstitutionAction.JOIN)
        }

        assertEquals("医生已加入该机构", error.message)
    }

    @Test
    fun `join approval restores relationship selects first primary and never binds projects`() {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctors d") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("doctor-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns listOf("机构一")
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("FROM doctor_institutions") && it.contains("institution_id = ?") &&
                        it.contains("status = 'APPROVED'")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns emptyList()
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("FROM doctor_institutions") && it.contains("institution_id = ?") &&
                        !it.contains("status = 'APPROVED'")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns listOf("relationship-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT institution_id") && it.contains("LIMIT 1 FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("institution-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT name") && !it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns listOf("机构一")
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("COUNT(*)") && it.contains("doctor_institutions") },
                Long::class.java,
                "doctor-1"
            )
        } returns 0L
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        service.approveJoin("doctor-1", "institution-1", "legal-1")

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("UPDATE doctor_institutions") && it.contains("status = 'APPROVED'") },
                1,
                "legal-1",
                "relationship-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("UPDATE doctors") && it.contains("institution_name") },
                "institution-1",
                "机构一",
                "doctor-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT institution_id") && it.contains("LIMIT 1 FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        }
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO doctor_projects") }, *anyVararg()) }
    }

    @Test
    fun `leave approval removes only target live bindings reassigns primary and preserves orders`() {
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctors d") && it.contains("FOR UPDATE") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("doctor-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns listOf("机构一")
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("SELECT id FROM doctor_institutions") &&
                        it.contains("institution_id = ?") && it.contains("status = 'APPROVED'")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns listOf("relationship-1")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT institution_id") && it.contains("doctor_institutions") },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("institution-2")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT name") && it.contains("institutions") },
                String::class.java,
                "institution-2"
            )
        } returns listOf("机构二")

        service.approveLeave("doctor-1", "institution-1", "legal-1")

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("DELETE dp FROM doctor_projects") && it.contains("ip.institution_id = ?") },
                "doctor-1",
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("doctor_institution_project_configs") && it.contains("deleted_at = NOW()") },
                "doctor-1",
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("WITHDRAWN") },
                "legal-1",
                "doctor-1",
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("split_config_proposals") && it.contains("WITHDRAWN") },
                "legal-1",
                "doctor-1",
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("professional_project_requests") && it.contains("REJECTED") },
                "legal-1",
                "doctor-1",
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("UPDATE doctors") && it.contains("institution_name") },
                "institution-2",
                "机构二",
                "doctor-1"
            )
        }
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("orders", ignoreCase = true) }, *anyVararg()) }
    }
}

class JdbcDoctorInstitutionChangeRequestStoreTest {
    @Test
    fun `active institution query requires verified and not deleted`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForObject(
                match<String> {
                    it.contains("is_verified = 1") && it.contains("deleted_at IS NULL")
                },
                Long::class.java,
                "institution-1"
            )
        } returns 1L

        val active = JdbcDoctorInstitutionChangeRequestStore(jdbcTemplate)
            .isActiveInstitution("institution-1")

        assertTrue(active)
    }

    @Test
    fun `pending request eligibility uses a current locking ledger read`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("doctor_institution_change_requests") &&
                        it.contains("status = 'PENDING'") && it.contains("FOR UPDATE")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns listOf("request-1")

        val pending = JdbcDoctorInstitutionChangeRequestStore(jdbcTemplate)
            .hasPending("doctor-1", "institution-1")

        assertTrue(pending)
    }
}
