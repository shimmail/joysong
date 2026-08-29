package com.joysong.server.admin.controller

import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.refund.service.RefundEvidenceFileService
import com.joysong.server.refund.service.RefundService
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [AdminRefundController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
class AdminRefundEvidenceSecurityHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockBean lateinit var refundService: RefundService
    @MockBean lateinit var refundEvidenceFileService: RefundEvidenceFileService
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository

    @Test
    fun `anonymous refund evidence content request returns 401`() {
        mockMvc.perform(get("/api/admin/refunds/refund-1/evidence/file-1/content"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @WithMockUser(roles = ["USER"])
    fun `authenticated non-admin refund evidence content request returns 403`() {
        mockMvc.perform(get("/api/admin/refunds/refund-1/evidence/file-1/content"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `admin request for missing or mismatched refund evidence returns 404`() {
        given(refundEvidenceFileService.loadContentForAdmin("refund-1", "file-from-refund-2"))
            .willThrow(IllegalArgumentException("REFUND_EVIDENCE_NOT_FOUND"))

        mockMvc.perform(
            get("/api/admin/refunds/refund-1/evidence/file-from-refund-2/content")
                .with(user("admin").roles("ADMIN"))
        ).andExpect(status().isNotFound)
    }
}
