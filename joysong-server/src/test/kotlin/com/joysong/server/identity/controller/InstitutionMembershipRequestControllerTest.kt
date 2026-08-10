package com.joysong.server.identity.controller

import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.time.LocalDateTime

class InstitutionMembershipRequestControllerTest {

    @Test
    fun `submit uses authenticated actor instead of accepting an applicant id`() {
        val accessService = mockk<ManagementAccessService>()
        val requestService = mockk<InstitutionMembershipRequestService>()
        val authentication = mockk<Authentication>()
        val actor = actor()
        val expected = requestView()
        every { accessService.actor(authentication) } returns actor
        every {
            requestService.submit(actor, MembershipRequestType.DOCTOR, "institution-1", "申请加入")
        } returns expected
        val controller = InstitutionMembershipRequestController(accessService, requestService)

        val response = controller.submit(
            authentication,
            SubmitInstitutionMembershipRequest("DOCTOR", "institution-1", "申请加入")
        )

        assertEquals(200, response.code)
        assertEquals(expected, response.data)
        verify(exactly = 1) {
            requestService.submit(actor, MembershipRequestType.DOCTOR, "institution-1", "申请加入")
        }
    }

    private fun actor() = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = setOf("doctor-1")
    )

    private fun requestView() = InstitutionMembershipRequestView(
        id = "request-1",
        requestType = MembershipRequestType.DOCTOR,
        userId = "doctor-1",
        institutionId = "institution-1",
        status = "PENDING",
        requestNote = "申请加入",
        reviewNote = "",
        createdAt = LocalDateTime.of(2026, 8, 10, 10, 0),
        updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
    )
}
