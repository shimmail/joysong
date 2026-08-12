package com.joysong.server.article.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.article.dto.DoctorArticleView
import com.joysong.server.article.service.ArticleService
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.time.LocalDateTime

class DoctorArticleManagementControllerTest {
    private val service = mockk<ArticleService>()
    private val access = mockk<ManagementAccessService>()
    private val mvc = MockMvcBuilders.standaloneSetup(DoctorArticleManagementController(service, access))
        .setControllerAdvice(GlobalExceptionHandler()).build()
    private val auth = TestingAuthenticationToken("doctor-1", null)

    @Test
    fun `create returns 201 and dedicated view`() {
        every { access.actor(any()) } returns ManagementActor("doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1"))
        every { service.createForManagement(any(), any()) } returns DoctorArticleView(
            "a1", "title", "doctor", "summary", "cover", LocalDate.parse("2026-08-12"), "body", 0,
            "doctor-1", LocalDateTime.parse("2026-08-12T10:00:00"), null
        )
        mvc.perform(post("/api/management/doctor-articles").principal(auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"title":"title","summary":"summary","coverImage":"cover","publishDate":"2026-08-12","content":"body"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.doctorId").value("doctor-1"))
            .andExpect(jsonPath("$.data.readCount").value(0))
    }

    @Test
    fun `invalid body returns 400`() {
        mvc.perform(post("/api/management/doctor-articles").principal(auth)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"title":"","summary":"","coverImage":"","publishDate":"2026-08-12","content":""}"""))
            .andExpect(status().isBadRequest)
    }
}
