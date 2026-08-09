package com.joysong.server.admin.controller

import com.joysong.server.identity.service.AdminIdentityService
import com.joysong.server.identity.service.ConsultantBindingAdminView
import com.joysong.server.identity.service.PrivateIdentityFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication

class AdminIdentityControllerTest {

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
}
