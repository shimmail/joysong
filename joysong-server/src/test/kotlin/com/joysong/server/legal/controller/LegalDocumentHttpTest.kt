package com.joysong.server.legal.controller

import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.legal.dto.PublicLegalDocumentView
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.service.LegalDocumentService
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(PublicLegalDocumentController::class)
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
class LegalDocumentHttpTest @Autowired constructor(
    private val mockMvc: MockMvc
) {
    @MockBean lateinit var legalDocumentService: LegalDocumentService
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository

    @Test
    fun `anonymous JSON exposes the exact localized public view and quoted ETag`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(view())

        mockMvc.perform(get("/api/public/legal-documents/privacy-policy").param("locale", "en-US"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, "\"hash-en\""))
            .andExpect(jsonPath("$.data.type").value("privacy-policy"))
            .andExpect(jsonPath("$.data.locale").value("en-US"))
            .andExpect(jsonPath("$.data.contentSha256").value("hash-en"))
    }

    @Test
    fun `matching JSON ETag avoids sending the public document again`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(view())

        mockMvc.perform(
            get("/api/public/legal-documents/privacy-policy")
                .param("locale", "en-US")
                .header(HttpHeaders.IF_NONE_MATCH, "\"hash-en\"")
        )
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, "\"hash-en\""))
    }

    @Test
    fun `unpublished public document is a real HTTP 404`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(null)

        mockMvc.perform(get("/api/public/legal-documents/privacy-policy").param("locale", "en-US"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `anonymous HTML renders sanitized content without scripts or third party resources`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(view(title = "Privacy & safety", contentHtml = "<p>Safe <strong>content</strong></p>"))

        mockMvc.perform(get("/legal/privacy-policy").param("locale", "en-US"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(content().encoding("UTF-8"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<meta charset=\"UTF-8\">")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<meta name=\"viewport\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Privacy &amp; safety</title>")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http://"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("https://"))))
    }

    private fun view(
        title: String = "Privacy policy",
        contentHtml: String = "<p>Privacy body</p>"
    ) = PublicLegalDocumentView(
        type = "privacy-policy",
        locale = "en-US",
        version = 2,
        title = title,
        contentHtml = contentHtml,
        publishedAt = LocalDateTime.of(2026, 8, 25, 10, 0),
        contentSha256 = "hash-en"
    )
}
