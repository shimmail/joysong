package com.joysong.server.admin.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.identity.service.AdminIdentityService
import com.joysong.server.identity.service.ConsultantBindingAdminView
import com.joysong.server.identity.service.ConsultantInstitutionRequestConflictException
import com.joysong.server.identity.service.PrivateIdentityFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AdminIdentityControllerTest {

    @Test
    fun `pending consultant ledger conflict is exposed as HTTP 409 on admin binding`() {
        val adminIdentityService = mockk<AdminIdentityService>()
        every {
            adminIdentityService.bindConsultant("user-1", "institution-1", "admin-1")
        } throws ConsultantInstitutionRequestConflictException("该机构存在待审核的顾问关系申请，请先完成审核")
        val controller = AdminIdentityController(adminIdentityService, mockk<PrivateIdentityFileService>())
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mvc.perform(
            post("/api/admin/identity/consultants")
                .principal(UsernamePasswordAuthenticationToken("admin-1", "", emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":"user-1","institutionId":"institution-1"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
    }

    @Test
    fun `bindConsultant forwards request ids and authenticated admin as confirmer`() {
        val adminIdentityService = mockk<AdminIdentityService>()
        val authentication = mockk<Authentication>()
        val controller = AdminIdentityController(adminIdentityService, mockk<PrivateIdentityFileService>())
        val expected = ConsultantBindingAdminView(
            userId = "user-1",
            userName = "用户一",
            institutionId = "institution-1",
            institutionName = "机构一",
            membershipId = "membership-1",
            roleCode = "CONSULTANT",
            status = "APPROVED"
        )
        every { authentication.principal } returns "admin-1"
        every { adminIdentityService.bindConsultant("user-1", "institution-1", "admin-1") } returns expected

        val response = controller.bindConsultant(authentication, BindConsultantRequest("user-1", "institution-1"))

        assertEquals(200, response.code)
        assertEquals(expected, response.data)
        verify(exactly = 1) { adminIdentityService.bindConsultant("user-1", "institution-1", "admin-1") }
    }

    @Test
    fun `generic membership creation forwards authenticated admin as platform confirmer`() {
        val adminIdentityService = mockk<AdminIdentityService>()
        val authentication = mockk<Authentication>()
        val controller = AdminIdentityController(adminIdentityService, mockk<PrivateIdentityFileService>())
        every { authentication.principal } returns "admin-1"
        every {
            adminIdentityService.createMembership(
                "user-1",
                "institution-1",
                "CONSULTANT",
                "admin-1"
            )
        } returns "membership-1"

        val response = controller.createMembership(
            authentication,
            CreateMembershipRequest("user-1", "institution-1", "CONSULTANT")
        )

        assertEquals(200, response.code)
        assertEquals(mapOf("id" to "membership-1"), response.data)
    }

    @Test
    fun `membership and doctor revocation forward the authenticated admin reviewer`() {
        val adminIdentityService = mockk<AdminIdentityService>(relaxed = true)
        val authentication = mockk<Authentication>()
        val controller = AdminIdentityController(adminIdentityService, mockk<PrivateIdentityFileService>())
        every { authentication.principal } returns "admin-1"

        controller.revokeMembership(authentication, "membership-1")
        controller.revokeDoctorPractice(authentication, "doctor-request-1")

        verify(exactly = 1) { adminIdentityService.revokeMembership("membership-1", "admin-1") }
        verify(exactly = 1) { adminIdentityService.revokeDoctorPractice("doctor-request-1", "admin-1") }
    }
}
