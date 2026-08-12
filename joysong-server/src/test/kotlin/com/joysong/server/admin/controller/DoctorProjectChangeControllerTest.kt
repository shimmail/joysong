package com.joysong.server.admin.controller

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.DoctorProjectChangeRequest
import com.joysong.server.institution.service.DoctorProjectChangeService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class DoctorProjectChangeControllerTest {
    private val service = mockk<DoctorProjectChangeService>()
    private val access = mockk<ManagementAccessService>()
    private val authentication = mockk<Authentication>()
    private val controller = DoctorProjectChangeController(service, access)

    @Test
    fun `profile update targets derive doctor from authenticated actor`() {
        val actor = actor().copy(activeRoles = setOf("DOCTOR"), doctorId = "doctor-1")
        every { access.actor(authentication) } returns actor
        every { service.listProfileUpdateTargets(actor) } returns emptyList()

        val response = controller.listProfileUpdateTargets(authentication)

        verify(exactly = 1) { service.listProfileUpdateTargets(actor) }
        assertEquals(200, response.code)
    }

    @Test
    fun `submit forwards full twelve key profile update arrays`() {
        val actor = actor()
        val request = DoctorProjectChangeRequest("ip-1", "PROFILE_UPDATE", "service", BigDecimal("880"), "notes", listOf("tag"), "schedule", "cover", listOf("image"), BigDecimal("30"), BigDecimal("10"), BigDecimal("40"))
        every { access.actor(authentication) } returns actor
        every { service.submit(actor, request) } returns mockk()

        controller.submit(authentication, request)

        verify(exactly = 1) { service.submit(actor, request) }
        assertEquals(listOf("tag"), request.serviceTags)
        assertEquals(listOf("image"), request.images)
    }

    @Test
    fun `review requires explicit force and service rejects legal force`() {
        val actor = actor(isAdmin = false)
        every { access.actor(authentication) } returns actor
        every { service.review(actor, "r-1", "APPROVED", "override", true) } throws AccessDeniedException("只有平台管理员可以强制处理")

        assertThrows(AccessDeniedException::class.java) {
            controller.review(authentication, "r-1", ProjectChangeReviewRequest("APPROVED", "override", true))
        }
        verify { service.review(actor, "r-1", "APPROVED", "override", true) }
    }

    private fun actor(isAdmin: Boolean = false) = ManagementActor("legal-1", isAdmin, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, setOf("institution-1"), emptySet(), emptySet())
}
