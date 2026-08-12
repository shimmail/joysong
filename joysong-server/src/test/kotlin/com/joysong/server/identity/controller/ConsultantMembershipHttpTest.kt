package com.joysong.server.identity.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.*
import com.joysong.server.user.repository.UserRepository
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.LocalDateTime

@WebMvcTest
@ContextConfiguration(classes = [ConsultantMembershipHttpConfig::class, SecurityConfig::class, GlobalExceptionHandler::class])
class ConsultantMembershipHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: ManagementAccessService
    @Autowired lateinit var service: InstitutionMembershipRequestService
    private val actor = ManagementActor("consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet())
    private val now = LocalDateTime.of(2026, 8, 12, 10, 0)

    @BeforeEach fun reset() = clearMocks(access, service)

    @Test @WithMockUser(username = "consultant-1")
    fun `GET and POST return decodable consultant projections`() {
        val view = InstitutionMembershipRequestView(
            "m-1", MembershipRequestType.CONSULTANT, "consultant-1", "i-1", "PENDING",
            "加入", "", now, now, institutionName = "机构一", confirmedBy = "reviewer", confirmedAt = now
        )
        every { access.actor(any()) } returns actor
        every { service.listOwnedConsultant(actor) } returns listOf(view)
        every { service.submitConsultant(actor, "i-1", "加入") } returns view

        mvc.perform(get("/api/management/consultant-memberships"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data[0].institutionName").value("机构一"))
            .andExpect(jsonPath("$.data[0].confirmedBy").value("reviewer"))
        mvc.perform(post("/api/management/consultant-memberships").contentType(MediaType.APPLICATION_JSON)
            .content("""{"institutionId":"i-1","requestNote":"加入"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.institutionName").value("机构一"))
            .andExpect(jsonPath("$.data.confirmedAt").exists())
    }

    @Test @WithMockUser(username = "consultant-1")
    fun `POST unknown missing and malformed fields are HTTP 400`() {
        listOf(
            """{"institutionId":"i-1","requestNote":"加入","unknown":true}""",
            """{"institutionId":"i-1"}""",
            """{"institutionId":"""
        ).forEach { body ->
            mvc.perform(post("/api/management/consultant-memberships").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value(400))
        }
    }

    @Test @WithMockUser(username = "user-1")
    fun `inactive user is HTTP 403 and missing institution and conflict keep real status bodies`() {
        every { access.actor(any()) } throws AccessDeniedException("账号尚未取得专业身份管理权限")
        mvc.perform(get("/api/management/consultant-memberships"))
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value(403))

        every { access.actor(any()) } returns actor
        every { service.submitConsultant(actor, any(), any()) } throws ConsultantInstitutionNotFoundException("机构不存在")
        mvc.perform(post("/api/management/consultant-memberships").contentType(MediaType.APPLICATION_JSON)
            .content("""{"institutionId":"missing","requestNote":""}"""))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value(404))

        every { service.submitConsultant(actor, any(), any()) } throws ConsultantMembershipConflictException("已处理")
        mvc.perform(post("/api/management/consultant-memberships").contentType(MediaType.APPLICATION_JSON)
            .content("""{"institutionId":"i-1","requestNote":""}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value(409))
    }

    @Test @WithMockUser(username = "consultant-1")
    fun `membership PUT and project POST are method not allowed`() {
        mvc.perform(put("/api/management/consultant-memberships")).andExpect(status().isMethodNotAllowed)
        mvc.perform(post("/api/management/projects")).andExpect(status().isMethodNotAllowed)
    }
}

@TestConfiguration
@Import(ConsultantMembershipController::class, com.joysong.server.project.controller.ManagementProjectController::class, JwtAuthenticationFilter::class)
class ConsultantMembershipHttpConfig {
    @Bean fun access(): ManagementAccessService = mockk()
    @Bean fun membership(): InstitutionMembershipRequestService = mockk()
    @Bean fun token(): JwtTokenProvider = mockk(relaxed = true)
    @Bean fun users(): UserRepository = mockk(relaxed = true)
    @Bean fun projects(): com.joysong.server.project.controller.ManagementProjectCatalogService = mockk()
}
