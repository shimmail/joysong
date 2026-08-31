package com.joysong.server.config

import com.joysong.server.legal.controller.PublicLegalDocumentController
import com.joysong.server.admin.controller.AdminLegalDocumentController
import com.joysong.server.legal.service.LegalDocumentConflictException
import com.joysong.server.legal.service.LegalDocumentService
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.willThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest(controllers = [
    PublicLegalDocumentController::class,
    AdminLegalDocumentController::class,
    SecurityConfigAdminAuthorizationTestController::class,
])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
class SecurityConfigLegalDocumentsTest @Autowired constructor(
    private val mockMvc: MockMvc
) {
    @MockBean lateinit var legalDocumentService: LegalDocumentService
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository

    @Test
    fun `anonymous admin legal document write is rejected with 401`() {
        mockMvc.perform(post("/api/admin/legal-documents/privacy-policy/draft"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `non admin legal document write is rejected with 403`() {
        mockMvc.perform(post("/api/admin/legal-documents/privacy-policy/draft"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `non admin cannot use admin route that previously accepted any authenticated user`() {
        mockMvc.perform(get("/api/admin/doctors"))
            .andExpect(status().isForbidden)
    }

    @Test
    @WithMockUser(username = "admin-1", roles = ["ADMIN"])
    fun `database draft conflict is exposed as HTTP 409`() {
        willThrow(LegalDocumentConflictException("该协议已有草稿"))
            .given(legalDocumentService)
            .createDraft(com.joysong.server.legal.entity.LegalDocumentType.PRIVACY_POLICY, "admin-1")

        mockMvc.perform(post("/api/admin/legal-documents/privacy-policy/draft"))
            .andExpect(status().isConflict)
    }
}

@TestComponent
@RestController
class SecurityConfigAdminAuthorizationTestController {
    @GetMapping("/api/admin/doctors")
    fun listDoctors(): String = "ok"
}
