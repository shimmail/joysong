package com.joysong.server.identity.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import io.mockk.mockk
import io.mockk.verify
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDateTime

class InstitutionMembershipRequestServiceTest {
    private val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)

    private fun service(store: InstitutionMembershipRequestStore = FakeMembershipRequestStore()) =
        InstitutionMembershipRequestService(store, relationshipService)

    @Test
    fun `legacy doctor submission is rejected in favor of the relationship ledger`() {
        val store = FakeMembershipRequestStore()
        val service = service(store)

        assertThrows(IllegalArgumentException::class.java) {
            service.submit(
                doctorActor(),
                MembershipRequestType.DOCTOR,
                "institution-1",
                "希望加入"
            )
        }
    }

    @Test
    fun `consultant request requires consultant role`() {
        val service = service()

        assertThrows(AccessDeniedException::class.java) {
            service.submit(doctorActor(), MembershipRequestType.CONSULTANT, "institution-1", "申请加入")
        }
    }

    @Test
    fun `list contains only own requests and requests in managed institutions`() {
        val store = FakeMembershipRequestStore().apply {
            seed(request("own", MembershipRequestType.CONSULTANT, "legal-1", "institution-9"))
            seed(request("managed", MembershipRequestType.DOCTOR, "doctor-2", "institution-1"))
            seed(request("hidden", MembershipRequestType.DOCTOR, "doctor-3", "institution-2"))
        }
        val actor = legalActor("institution-1")

        val requests = service(store).list(actor)

        assertEquals(listOf("managed", "own"), requests.map { it.id }.sorted())
    }

    @Test
    fun `platform admin list includes all rolling legacy requests`() {
        val store = FakeMembershipRequestStore().apply {
            seed(request("doctor-pending", MembershipRequestType.DOCTOR, "doctor-2", "institution-1"))
            seed(request("consultant-pending", MembershipRequestType.CONSULTANT, "consultant-2", "institution-2"))
        }

        val requests = service(store).list(adminActor())

        assertEquals(listOf("consultant-pending", "doctor-pending"), requests.map { it.id }.sorted())
    }

    @Test
    fun `legal representative cannot review a request from another institution`() {
        val store = FakeMembershipRequestStore().apply {
            seed(request("request-1", MembershipRequestType.DOCTOR, "doctor-2", "institution-2"))
        }

        assertThrows(AccessDeniedException::class.java) {
            service(store).review(
                legalActor("institution-1"),
                MembershipRequestType.DOCTOR,
                "request-1",
                MembershipRequestDecision.REJECTED,
                "材料不符"
            )
        }
        assertEquals("PENDING", store.get(MembershipRequestType.DOCTOR, "request-1")?.status)
    }

    @Test
    fun `rejection requires a review note`() {
        val store = FakeMembershipRequestStore().apply {
            seed(request("request-1", MembershipRequestType.CONSULTANT, "consultant-1", "institution-1"))
        }
        val service = service(store)

        assertThrows(IllegalArgumentException::class.java) {
            service.review(
                adminActor(),
                MembershipRequestType.CONSULTANT,
                "request-1",
                MembershipRequestDecision.REJECTED,
                "  "
            )
        }
    }

    @Test
    fun `membership decisions reject changes requested`() {
        assertThrows(IllegalArgumentException::class.java) {
            MembershipRequestDecision.parse("CHANGES_REQUESTED")
        }
    }

    @Test
    fun `legacy doctor join approval applies relationship effects before approving request`() {
        val store = FakeMembershipRequestStore().apply {
            seed(request("legacy-join", MembershipRequestType.DOCTOR, "doctor-2", "institution-1"))
        }

        service(store).review(
            legalActor("institution-1"), MembershipRequestType.DOCTOR, "legacy-join",
            MembershipRequestDecision.APPROVED, ""
        )

        verify { relationshipService.approveJoin("doctor-2", "institution-1", "legal-1") }
        assertEquals("APPROVED", store.get(MembershipRequestType.DOCTOR, "legacy-join")?.status)
    }

    private fun doctorActor() = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = setOf("doctor-1")
    )

    private fun consultantActor() = ManagementActor(
        userId = "consultant-1",
        isAdmin = false,
        activeRoles = setOf("CONSULTANT"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun legalActor(institutionId: String) = ManagementActor(
        userId = "legal-1",
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = setOf(institutionId),
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

    private fun request(
        id: String,
        type: MembershipRequestType,
        userId: String,
        institutionId: String,
        status: String = "PENDING",
        reviewNote: String = ""
    ) = InstitutionMembershipRequestView(
        id = id,
        requestType = type,
        userId = userId,
        institutionId = institutionId,
        status = status,
        requestNote = "申请加入",
        reviewNote = reviewNote,
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )
}

private class FakeMembershipRequestStore : InstitutionMembershipRequestStore {
    private val requests = linkedMapOf<Pair<MembershipRequestType, String>, InstitutionMembershipRequestView>()

    fun seed(request: InstitutionMembershipRequestView) {
        requests[request.requestType to request.id] = request
    }

    fun get(type: MembershipRequestType, id: String) = requests[type to id]

    override fun find(type: MembershipRequestType, userId: String, institutionId: String) =
        requests.values.firstOrNull {
            it.requestType == type && it.userId == userId && it.institutionId == institutionId
        }

    override fun create(
        type: MembershipRequestType,
        userId: String,
        institutionId: String,
        requestNote: String
    ): InstitutionMembershipRequestView = requestView(type, userId, institutionId, requestNote).also(::seed)

    override fun resubmit(
        request: InstitutionMembershipRequestView,
        requestNote: String
    ): InstitutionMembershipRequestView = request.copy(
        status = "PENDING",
        requestNote = requestNote,
        reviewNote = "",
        updatedAt = request.updatedAt.plusMinutes(1)
    ).also(::seed)

    override fun listVisible(userId: String, managedInstitutionIds: Set<String>) = requests.values
        .filter { it.userId == userId || it.institutionId in managedInstitutionIds }

    override fun listAll() = requests.values.toList()

    override fun findById(type: MembershipRequestType, id: String) = requests[type to id]

    override fun review(
        request: InstitutionMembershipRequestView,
        reviewerId: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): InstitutionMembershipRequestView = request.copy(
        status = decision.name,
        reviewNote = reviewNote,
        updatedAt = request.updatedAt.plusMinutes(1)
    ).also(::seed)

    private fun requestView(
        type: MembershipRequestType,
        userId: String,
        institutionId: String,
        requestNote: String
    ) = InstitutionMembershipRequestView(
        id = "created-${requests.size + 1}",
        requestType = type,
        userId = userId,
        institutionId = institutionId,
        status = "PENDING",
        requestNote = requestNote,
        reviewNote = "",
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )
}
