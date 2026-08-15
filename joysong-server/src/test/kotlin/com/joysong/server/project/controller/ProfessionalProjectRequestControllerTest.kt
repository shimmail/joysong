package com.joysong.server.project.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.project.service.ProfessionalProjectRequestService
import com.joysong.server.project.service.ProjectRequestReview
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.RequestMapping

class ProfessionalProjectRequestControllerTest {
    @Test
    fun `management and admin controllers use separate route roots`() {
        assertEquals(
            "/api/management/project-requests",
            ProfessionalProjectRequestController::class.java.getAnnotation(RequestMapping::class.java).value.single()
        )
        assertEquals(
            "/api/admin/project-requests",
            AdminProfessionalProjectRequestController::class.java.getAnnotation(RequestMapping::class.java).value.single()
        )
    }

    @Test
    fun `management review resolves actor and delegates institution review`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val service = mockk<ProfessionalProjectRequestService>()
        val actor = ManagementActor("representative-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, setOf("institution-1"), emptySet(), emptySet())
        val request = ProjectRequestReview("APPROVED")
        every { access.actor(authentication) } returns actor
        every { service.reviewInstitution(actor, "request-1", request) } returns mockk()

        ProfessionalProjectRequestController(service, access, mockk<OrderSplitRatePolicy>(), jacksonObjectMapper())
            .reviewInstitution(authentication, "request-1", request)

        verify(exactly = 1) { service.reviewInstitution(actor, "request-1", request) }
    }

    @Test
    fun `admin review resolves actor and delegates platform review`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val service = mockk<ProfessionalProjectRequestService>()
        val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
        val request = ProjectRequestReview("REJECTED", "duplicate")
        every { access.actor(authentication) } returns actor
        every { service.reviewPlatform(actor, "request-1", request) } returns mockk()

        AdminProfessionalProjectRequestController(service, access)
            .review(authentication, "request-1", request)

        verify(exactly = 1) { service.reviewPlatform(actor, "request-1", request) }
    }
}
