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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class DoctorProjectChangeControllerTest {
    private val service = mockk<DoctorProjectChangeService>()
    private val access = mockk<ManagementAccessService>()
    private val authentication = mockk<Authentication>()
    private val mapper = jacksonObjectMapper()
    private val controller = DoctorProjectChangeController(service, access, mapper)

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
    fun `submit forwards profile update with medical list price`() {
        val actor = actor()
        val request = DoctorProjectChangeRequest("ip-1", "PROFILE_UPDATE", "service", BigDecimal("880"), "notes", listOf("tag"), "schedule", "cover", listOf("image"), BigDecimal("30"), BigDecimal("10"), BigDecimal("40"), BigDecimal("1000.00"))
        every { access.actor(authentication) } returns actor
        every { service.submit(actor, match { it.requestType == "PROFILE_UPDATE" && it.serviceTags == listOf("tag") && it.images == listOf("image") && it.medicalListPrice?.compareTo(BigDecimal("1000.00")) == 0 }) } returns mockk()

        controller.submit(authentication, mapper.valueToTree(request))

        verify(exactly = 1) { service.submit(actor, match { it.requestType == "PROFILE_UPDATE" && it.serviceTags == listOf("tag") && it.images == listOf("image") && it.medicalListPrice?.compareTo(BigDecimal("1000.00")) == 0 }) }
        assertEquals(listOf("tag"), request.serviceTags)
        assertEquals(listOf("image"), request.images)
    }

    @Test
    fun `profile update rejects doctor supplied platform rate`() {
        every { access.actor(authentication) } returns actor()
        val request = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(DoctorProjectChangeRequest(
            institutionProjectId = "ip-1", requestType = "PROFILE_UPDATE", serviceDescription = "service",
            priceSuggestion = BigDecimal("880"), notes = "notes", serviceTags = listOf("tag"), scheduleNote = "schedule",
            coverImage = "cover", images = listOf("image"), consultationFee = BigDecimal("30"),
            commissionRate = BigDecimal("10"), institutionRate = BigDecimal("40"), medicalListPrice = BigDecimal("1000")
        )).also { (it as com.fasterxml.jackson.databind.node.ObjectNode).put("platformRate", "99.99") }

        val error = assertThrows(IllegalArgumentException::class.java) { controller.submit(authentication, request) }

        assertEquals("PROFILE_UPDATE 必须且仅能提交 13 个约定字段", error.message)
        verify(exactly = 0) { service.submit(any(), any()) }
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
