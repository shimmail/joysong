package com.joysong.server.config

import com.joysong.server.legal.controller.PublicLegalDocumentController
import com.joysong.server.legal.service.LegalDocumentService
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(PublicLegalDocumentController::class)
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
}
