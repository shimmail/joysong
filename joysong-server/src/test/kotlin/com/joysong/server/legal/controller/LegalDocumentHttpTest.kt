package com.joysong.server.legal.controller

import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.admin.controller.AdminLegalDocumentController
import com.joysong.server.legal.dto.AdminLegalDocumentSummaryView
import com.joysong.server.legal.dto.LegalDocumentContentView
import com.joysong.server.legal.dto.LegalDocumentReleaseSummaryView
import com.joysong.server.legal.dto.LegalDocumentReleaseView
import com.joysong.server.legal.dto.PublicLegalDocumentView
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentStatus
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(controllers = [PublicLegalDocumentController::class, AdminLegalDocumentController::class])
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
    fun `weak ETag in a comma separated JSON validator list avoids sending the document again`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(view())

        mockMvc.perform(
            get("/api/public/legal-documents/privacy-policy")
                .param("locale", "en-US")
                .header(HttpHeaders.IF_NONE_MATCH, "\"other\", W/\"hash-en\"")
        )
            .andExpect(status().isNotModified)
            .andExpect(header().string(HttpHeaders.ETAG, "\"hash-en\""))
    }

    @Test
    fun `wildcard JSON validator avoids sending any current document representation`() {
        given(legalDocumentService.findPublished(LegalDocumentType.PRIVACY_POLICY, LegalDocumentLocale.EN_US))
            .willReturn(view())

        mockMvc.perform(
            get("/api/public/legal-documents/privacy-policy")
                .param("locale", "en-US")
                .header(HttpHeaders.IF_NONE_MATCH, "*")
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
    fun `unsupported public document type and locale are real HTTP 400 for JSON and HTML`() {
        listOf(
            get("/api/public/legal-documents/terms").param("locale", "en-US"),
            get("/api/public/legal-documents/privacy-policy").param("locale", "fr-FR"),
            get("/legal/terms").param("locale", "en-US"),
            get("/legal/privacy-policy").param("locale", "fr-FR")
        ).forEach { request ->
            mockMvc.perform(request).andExpect(status().isBadRequest)
        }
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

    @Test
    fun `admin list and release serialize legal enum values as client wire slugs and tags`() {
        val summary = releaseSummary()
        given(legalDocumentService.listAdmin()).willReturn(
            listOf(AdminLegalDocumentSummaryView(LegalDocumentType.PRIVACY_POLICY, summary, null))
        )
        given(legalDocumentService.getRelease("release-1")).willReturn(releaseView())

        mockMvc.perform(get("/api/admin/legal-documents").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].documentType").value("privacy-policy"))
            .andExpect(jsonPath("$.data[0].draft.lockVersion").value(7))

        mockMvc.perform(get("/api/admin/legal-documents/releases/release-1").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.documentType").value("privacy-policy"))
            .andExpect(jsonPath("$.data.contents[0].locale").value("zh-CN"))
            .andExpect(jsonPath("$.data.contents[0].contentSha256").value("hash-zh"))
            .andExpect(jsonPath("$.data.lockVersion").value(7))
    }

    @Test
    fun `admin history returns only immutable published and superseded releases`() {
        given(legalDocumentService.history(LegalDocumentType.PRIVACY_POLICY)).willReturn(
            listOf(
                releaseSummary().copy(id = "published-2", status = LegalDocumentStatus.PUBLISHED),
                releaseSummary().copy(id = "superseded-1", status = LegalDocumentStatus.SUPERSEDED)
            )
        )

        mockMvc.perform(get("/api/admin/legal-documents/privacy-policy/history").with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].id").value("published-2"))
            .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
            .andExpect(jsonPath("$.data[1].id").value("superseded-1"))
            .andExpect(jsonPath("$.data[1].status").value("SUPERSEDED"))
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

    private fun releaseSummary() = LegalDocumentReleaseSummaryView(
        id = "release-1",
        documentType = LegalDocumentType.PRIVACY_POLICY,
        version = 2,
        status = LegalDocumentStatus.DRAFT,
        changeSummary = "Updated contact details",
        publishedAt = null,
        updatedAt = LocalDateTime.of(2026, 8, 26, 9, 0),
        lockVersion = 7
    )

    private fun releaseView() = LegalDocumentReleaseView(
        id = "release-1",
        documentType = LegalDocumentType.PRIVACY_POLICY,
        version = 2,
        status = LegalDocumentStatus.DRAFT,
        changeSummary = "Updated contact details",
        publishedAt = null,
        publishedBy = null,
        createdAt = LocalDateTime.of(2026, 8, 26, 8, 0),
        updatedAt = LocalDateTime.of(2026, 8, 26, 9, 0),
        lockVersion = 7,
        contents = listOf(
            LegalDocumentContentView(
                locale = LegalDocumentLocale.ZH_CN,
                title = "隐私政策",
                contentHtml = "<p>中文</p>",
                contentSha256 = "hash-zh"
            )
        )
    )
}
