package com.joysong.server.identity.service

import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.notification.service.ProfessionalApplicantRole
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.access.AccessDeniedException
import java.sql.SQLException
import java.time.LocalDateTime

class ConsultantInstitutionChangeRequestServiceTest {
    private val events = mutableListOf<String>()
    private val store = FakeChangeRequestStore(events)
    private val relationships = FakeRelationshipOperations(events)
    private val reviewAuthority = FakeReviewAuthority(events)
    private val businessNotifications = mockk<BusinessNotificationService>(relaxed = true)
    private val service = ConsultantInstitutionChangeRequestService(
        store,
        relationships,
        reviewAuthority,
        businessNotifications
    )

    @Test
    fun `only the active consultant submits a request for self`() {
        assertThrows(AccessDeniedException::class.java) {
            service.submit(actor(activeRoles = setOf("USER")), "institution-1", ConsultantInstitutionAction.JOIN, "join")
        }

        val submitted = service.submit(
            actor(userId = "consultant-1"),
            " institution-1 ",
            ConsultantInstitutionAction.JOIN,
            "  join  "
        )

        assertEquals("consultant-1", submitted.consultantId)
        assertEquals("institution-1", submitted.institutionId)
        assertEquals("join", submitted.requestNote)
        io.mockk.verify(exactly = 1) {
            businessNotifications.professionalApplicationSubmitted(
                "institution-1",
                ProfessionalApplicantRole.CONSULTANT,
                "new-1"
            )
        }
    }

    @Test
    fun `submit locks the pair then revalidates the active consultant role before current reads`() {
        relationships.activeConsultant = false

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "")
        }

        assertEquals(listOf("pair", "role"), events)
        assertTrue(store.created.isEmpty())
    }

    @Test
    fun `join requires a verified institution and no active relationship`() {
        relationships.activeInstitutions.clear()
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "")
        }

        relationships.activeInstitutions += "institution-1"
        store.activeRelationship = true
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "")
        }
        assertTrue(store.created.isEmpty())
    }

    @Test
    fun `leave requires an active approved non-revoked consultant relationship`() {
        store.activeRelationship = false

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.LEAVE, "")
        }

        store.activeRelationship = true
        val request = service.submit(actor(), "institution-1", ConsultantInstitutionAction.LEAVE, "leave")
        assertEquals(ConsultantInstitutionAction.LEAVE, request.action)
    }

    @Test
    fun `one pending request blocks both actions before insert`() {
        store.pending = true

        ConsultantInstitutionAction.entries.forEach { action ->
            store.activeRelationship = action == ConsultantInstitutionAction.LEAVE
            val error = assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
                service.submit(actor(), "institution-1", action, "")
            }
            assertEquals("该机构已有待处理的关系申请", error.message)
        }
        assertTrue(store.created.isEmpty())
    }

    @Test
    fun `only the named pending unique key race becomes a typed conflict`() {
        store.createFailure = DuplicateKeyException(
            "pending race",
            SQLException(
                "Duplicate entry for key 'uk_consultant_institution_change_requests_pending'",
                "23000",
                1062
            )
        )

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "")
        }

        store.createFailure = DuplicateKeyException(
            "unrelated",
            SQLException("Duplicate entry for key 'fk_or_other_key'", "23000", 1062)
        )
        assertThrows(DuplicateKeyException::class.java) {
            service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "")
        }
        io.mockk.verify(exactly = 0) {
            businessNotifications.professionalApplicationSubmitted(any(), any(), any())
        }
    }

    @Test
    fun `request note is trimmed and limited to one thousand characters`() {
        val request = service.submit(
            actor(),
            "institution-1",
            ConsultantInstitutionAction.JOIN,
            "  ${"x".repeat(1000)}  "
        )
        assertEquals(1000, request.requestNote.length)

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(actor(), "institution-2", ConsultantInstitutionAction.JOIN, "x".repeat(1001))
        }
    }

    @Test
    fun `resubmission appends a ledger row without overwriting rejected or withdrawn history`() {
        val rejected = request(id = "history-rejected", status = ConsultantInstitutionRequestStatus.REJECTED)
        val withdrawn = request(id = "history-withdrawn", status = ConsultantInstitutionRequestStatus.WITHDRAWN)
        store.history += listOf(rejected, withdrawn)

        val resubmitted = service.submit(actor(), "institution-1", ConsultantInstitutionAction.JOIN, "new")

        assertTrue(resubmitted.id !in setOf(rejected.id, withdrawn.id))
        assertEquals(ConsultantInstitutionRequestStatus.REJECTED, store.history[0].status)
        assertEquals(ConsultantInstitutionRequestStatus.WITHDRAWN, store.history[1].status)
    }

    @Test
    fun `only the request owner withdraws their pending request`() {
        store.locked = request(submittedBy = "consultant-1")

        listOf(
            actor(userId = "admin-1", isAdmin = true, activeRoles = setOf("ADMIN")),
            actor(userId = "legal-1", activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), managed = setOf("institution-1"))
        ).forEach { forgedActor ->
            assertThrows(AccessDeniedException::class.java) {
                service.withdraw(forgedActor, "request-1")
            }
        }

        val withdrawn = service.withdraw(actor(), "request-1")
        assertEquals(ConsultantInstitutionRequestStatus.WITHDRAWN, withdrawn.status)
        assertEquals(1, store.statusChanges)
        io.mockk.verify(exactly = 1) {
            businessNotifications.professionalApplicationWithdrawn(
                "institution-1",
                ProfessionalApplicantRole.CONSULTANT,
                "request-1"
            )
        }
    }

    @Test
    fun `withdraw rejects a request already closed by another operation with a typed conflict`() {
        store.locked = request(status = ConsultantInstitutionRequestStatus.APPROVED)

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.withdraw(actor(), "request-1")
        }

        store.locked = request()
        store.changeStatusResult = false
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.withdraw(actor(), "request-1")
        }
    }

    @Test
    fun `withdraw peeks then pair locks before relocking the request`() {
        store.locked = request()

        service.withdraw(actor(), "request-1")

        assertEquals(listOf("peek", "pair", "request", "status"), events)
    }

    @Test
    fun `legal representative reviews only a managed institution while admin reviews globally`() {
        store.locked = request(institutionId = "institution-1")
        assertThrows(AccessDeniedException::class.java) {
            service.review(
                actor(userId = "legal-1", activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), managed = setOf("institution-2")),
                "request-1",
                MembershipRequestDecision.REJECTED,
                "not eligible"
            )
        }

        val reviewed = service.review(
            actor(userId = "admin-1", isAdmin = true, activeRoles = setOf("ADMIN")),
            "request-1",
            MembershipRequestDecision.REJECTED,
            "not eligible"
        )
        assertEquals(ConsultantInstitutionRequestStatus.REJECTED, reviewed.status)
        io.mockk.verify(exactly = 1) {
            businessNotifications.professionalApplicationRejected(
                "consultant-1",
                ProfessionalApplicantRole.CONSULTANT,
                "request-1",
                "not eligible"
            )
        }
    }

    @Test
    fun `review requires pending status and a rejection reason`() {
        store.locked = request(status = ConsultantInstitutionRequestStatus.APPROVED)
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.review(admin(), "request-1", MembershipRequestDecision.APPROVED, "")
        }

        store.locked = request()
        assertThrows(IllegalArgumentException::class.java) {
            service.review(admin(), "request-1", MembershipRequestDecision.REJECTED, "   ")
        }
    }

    @Test
    fun `review revalidates institution and relationship before conditionally closing request`() {
        store.locked = request()
        relationships.reviewFailure = ConsultantInstitutionRequestConflictException("relationship changed")

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.review(admin(), "request-1", MembershipRequestDecision.APPROVED, "")
        }
        assertEquals(0, store.statusChanges)

        relationships.reviewFailure = null
        store.changeStatusResult = false
        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.review(admin(), "request-1", MembershipRequestDecision.APPROVED, "")
        }
        assertEquals(listOf("relationship", "status"), events.takeLast(2))
    }

    @Test
    fun `review locks sorted users and institution then current authority before relocking request`() {
        store.locked = request()

        service.review(admin(), "request-1", MembershipRequestDecision.REJECTED, "not eligible")

        assertEquals(
            listOf("peek", "users:admin-1,consultant-1", "institution", "role", "authority", "relationship", "request"),
            events.take(7)
        )
    }

    @Test
    fun `stale legal representative is denied by the common current authority check`() {
        store.locked = request()
        reviewAuthority.failure = AccessDeniedException("当前法人权限已失效")

        assertThrows(AccessDeniedException::class.java) {
            service.review(
                actor(
                    userId = "legal-1",
                    activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
                    managed = setOf("institution-1")
                ),
                "request-1",
                MembershipRequestDecision.APPROVED,
                ""
            )
        }

        assertEquals(0, store.statusChanges)
        assertTrue(relationships.approvedEffects.isEmpty())
    }

    @Test
    fun `platform admin remains an explicit common authority bypass`() {
        store.locked = request()

        val reviewed = service.review(
            admin(),
            "request-1",
            MembershipRequestDecision.REJECTED,
            "not eligible"
        )

        assertEquals(ConsultantInstitutionRequestStatus.REJECTED, reviewed.status)
        assertEquals(listOf("admin-1"), reviewAuthority.checkedActors)
    }

    @Test
    fun `withdrawal and rejection never apply a relationship effect`() {
        store.locked = request(action = ConsultantInstitutionAction.LEAVE)
        service.withdraw(actor(), "request-1")

        store.locked = request(action = ConsultantInstitutionAction.LEAVE)
        service.review(admin(), "request-1", MembershipRequestDecision.REJECTED, "not now")

        assertTrue(relationships.approvedEffects.isEmpty())
    }

    @Test
    fun `approval notifies the consultant only after the status transition succeeds`() {
        store.locked = request()

        service.review(admin(), "request-1", MembershipRequestDecision.APPROVED, "")

        io.mockk.verify(exactly = 1) {
            businessNotifications.professionalApplicationApproved(
                "consultant-1",
                ProfessionalApplicantRole.CONSULTANT,
                "request-1"
            )
        }
    }

    @Test
    fun `failed consultant status transition emits no professional notification`() {
        store.locked = request()
        store.changeStatusResult = false

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.withdraw(actor(), "request-1")
        }

        io.mockk.verify(exactly = 0) {
            businessNotifications.professionalApplicationWithdrawn(any(), any(), any())
        }
    }

    private fun actor(
        userId: String = "consultant-1",
        isAdmin: Boolean = false,
        activeRoles: Set<String> = setOf("CONSULTANT"),
        managed: Set<String> = emptySet()
    ) = ManagementActor(
        userId = userId,
        isAdmin = isAdmin,
        activeRoles = activeRoles,
        doctorId = null,
        managedInstitutionIds = managed,
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun admin() = actor(userId = "admin-1", isAdmin = true, activeRoles = setOf("ADMIN"))

    private fun request(
        id: String = "request-1",
        consultantId: String = "consultant-1",
        institutionId: String = "institution-1",
        action: ConsultantInstitutionAction = ConsultantInstitutionAction.JOIN,
        status: ConsultantInstitutionRequestStatus = ConsultantInstitutionRequestStatus.PENDING,
        submittedBy: String = consultantId
    ) = ConsultantInstitutionChangeRequestView(
        id = id,
        consultantId = consultantId,
        consultantName = "Consultant One",
        institutionId = institutionId,
        institutionName = "Institution One",
        action = action,
        status = status,
        requestNote = "note",
        reviewNote = "",
        submittedBy = submittedBy,
        reviewedBy = null,
        submittedAt = NOW,
        reviewedAt = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private class FakeChangeRequestStore(
        private val events: MutableList<String>
    ) : ConsultantInstitutionChangeRequestStore {
        var activeRelationship = false
        var pending = false
        var locked: ConsultantInstitutionChangeRequestView? = null
        var createFailure: DuplicateKeyException? = null
        var changeStatusResult = true
        var statusChanges = 0
        val created = mutableListOf<ConsultantInstitutionChangeRequestView>()
        val history = mutableListOf<ConsultantInstitutionChangeRequestView>()

        override fun create(
            consultantId: String,
            institutionId: String,
            action: ConsultantInstitutionAction,
            requestNote: String
        ): ConsultantInstitutionChangeRequestView {
            createFailure?.let { throw it }
            return request(created.size + history.size + 1, consultantId, institutionId, action, requestNote).also(created::add)
        }

        override fun listOwned(consultantId: String) = (history + created).filter { it.consultantId == consultantId }

        override fun listReviewable(managedInstitutionIds: Set<String>, includeAll: Boolean) =
            (history + created).filter { includeAll || it.institutionId in managedInstitutionIds }

        override fun find(id: String): ConsultantInstitutionChangeRequestView? {
            events += "peek"
            return locked?.takeIf { it.id == id }
        }

        override fun lock(id: String): ConsultantInstitutionChangeRequestView? {
            events += "request"
            return locked?.takeIf { it.id == id }
        }

        override fun changeStatus(
            id: String,
            status: ConsultantInstitutionRequestStatus,
            actorId: String,
            reviewNote: String
        ): Boolean {
            events += "status"
            if (!changeStatusResult) return false
            statusChanges += 1
            locked = locked?.copy(
                status = status,
                reviewNote = reviewNote,
                reviewedBy = actorId.takeUnless { status == ConsultantInstitutionRequestStatus.WITHDRAWN },
                reviewedAt = NOW.takeUnless { status == ConsultantInstitutionRequestStatus.WITHDRAWN }
            )
            return true
        }

        override fun hasPending(consultantId: String, institutionId: String): Boolean {
            events += "pending"
            return pending
        }

        override fun hasActiveRelationship(consultantId: String, institutionId: String): Boolean {
            events += "membership"
            return activeRelationship
        }

        private fun request(
            ordinal: Int,
            consultantId: String,
            institutionId: String,
            action: ConsultantInstitutionAction,
            note: String
        ) = ConsultantInstitutionChangeRequestView(
            id = "new-$ordinal",
            consultantId = consultantId,
            consultantName = "Consultant One",
            institutionId = institutionId,
            institutionName = "Institution One",
            action = action,
            status = ConsultantInstitutionRequestStatus.PENDING,
            requestNote = note,
            reviewNote = "",
            submittedBy = consultantId,
            reviewedBy = null,
            submittedAt = NOW,
            reviewedAt = null,
            createdAt = NOW,
            updatedAt = NOW
        )
    }

    private class FakeRelationshipOperations(
        private val events: MutableList<String>
    ) : ConsultantInstitutionRelationshipOperations {
        val activeInstitutions = mutableSetOf("institution-1", "institution-2")
        val approvedEffects = mutableListOf<ConsultantInstitutionAction>()
        var activeConsultant = true
        var reviewFailure: RuntimeException? = null

        override fun lockUser(consultantId: String) {
            events += "user"
        }

        override fun lockUsers(userIds: Collection<String>) {
            val sortedIds = userIds.sorted().joinToString(",")
            events += "users:$sortedIds"
        }

        override fun lockInstitution(institutionId: String) {
            events += "institution"
        }

        override fun lockPair(consultantId: String, institutionId: String) {
            events += "pair"
        }

        override fun requireActiveConsultant(consultantId: String) {
            events += "role"
            if (!activeConsultant) {
                throw ConsultantInstitutionRequestConflictException("顾问身份已失效")
            }
        }

        override fun requireActiveInstitution(institutionId: String) {
            if (institutionId !in activeInstitutions) {
                throw ConsultantInstitutionRequestConflictException("机构不存在、未认证或已删除")
            }
        }

        override fun validateForReview(
            consultantId: String,
            institutionId: String,
            action: ConsultantInstitutionAction
        ) {
            reviewFailure?.let { throw it }
            events += "relationship"
        }

        override fun applyApproved(
            consultantId: String,
            institutionId: String,
            action: ConsultantInstitutionAction,
            reviewerId: String
        ) {
            reviewFailure?.let { throw it }
            events += "relationship"
            approvedEffects += action
        }

        override fun forceRevoke(consultantId: String, institutionId: String, reviewerId: String) {
            events += "force-revoke"
        }
    }

    private class FakeReviewAuthority(
        private val events: MutableList<String>
    ) : InstitutionRelationshipReviewAuthorityOperations {
        var failure: AccessDeniedException? = null
        val checkedActors = mutableListOf<String>()

        override fun requireCurrentAuthority(actor: ManagementActor, institutionId: String) {
            events += "authority"
            checkedActors += actor.userId
            failure?.let { throw it }
        }
    }

    companion object {
        private val NOW = LocalDateTime.of(2026, 8, 14, 12, 0)
    }
}
