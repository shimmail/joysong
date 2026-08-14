package com.joysong.server.identity.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.ConsultantInstitutionRequestConflictException
import com.joysong.server.identity.service.ConsultantInstitutionRequestNotFoundException
import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestType
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest
@ContextConfiguration(classes = [ConsultantMembershipHttpConfig::class, SecurityConfig::class, GlobalExceptionHandler::class])
class ConsultantMembershipHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: ManagementAccessService
    @Autowired lateinit var service: InstitutionMembershipRequestService
    @Autowired lateinit var queries: InstitutionMembershipRequestQueryService

    private val actor = ManagementActor(
        "consultant-1", false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet()
    )

    @BeforeEach
    fun reset() = clearMocks(access, service, queries)

    @Test
    @WithMockUser(username = "consultant-1")
    fun `GET reads the scoped compatibility projection and POST dispatches strict JOIN`() {
        every { access.actor(any()) } returns actor
        every { queries.listConsultantCompatibility(actor) } returns listOf(unifiedView())
        every {
            service.submit(
                actor,
                MembershipRequestType.CONSULTANT,
                "institution-1",
                InstitutionMembershipAction.JOIN,
                "加入"
            )
        } returns unifiedView()

        mvc.perform(get("/api/management/consultant-memberships"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].institutionName").value("机构一"))
            .andExpect(jsonPath("$.data[0].status").value("PENDING"))
        mvc.perform(
            post("/api/management/consultant-memberships")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"institutionId":"institution-1","requestNote":"加入"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.institutionName").value("机构一"))
        verify(exactly = 1) {
            service.submit(
                actor,
                MembershipRequestType.CONSULTANT,
                "institution-1",
                InstitutionMembershipAction.JOIN,
                "加入"
            )
        }
        verify(exactly = 1) { queries.listConsultantCompatibility(actor) }
    }

    @Test
    @WithMockUser(username = "consultant-1")
    fun `POST unknown missing malformed and action fields are HTTP 400`() {
        every { access.actor(any()) } returns actor
        listOf(
            """{"institutionId":"institution-1","requestNote":"加入","unknown":true}""",
            """{"institutionId":"institution-1"}""",
            """{"institutionId":"institution-1","requestNote":"加入","action":"LEAVE"}""",
            """{"institutionId":""""
        ).forEach { body ->
            mvc.perform(
                post("/api/management/consultant-memberships")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `compatibility adapter preserves strict 403 404 and 409 statuses`() {
        val doctorOnly = ManagementActor(
            "doctor-1", false, setOf("DOCTOR"), "doctor-1",
            emptySet(), emptySet(), setOf("doctor-1")
        )
        every { access.actor(any()) } returns doctorOnly
        mvc.perform(get("/api/management/consultant-memberships"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))

        every { access.actor(any()) } returns actor
        every { service.submit(any(), any(), any(), any(), any()) } throws
            ConsultantInstitutionRequestNotFoundException("机构不存在")
        mvc.perform(
            post("/api/management/consultant-memberships")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"institutionId":"missing","requestNote":""}""")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))

        every { service.submit(any(), any(), any(), any(), any()) } throws
            ConsultantInstitutionRequestConflictException("重复待审")
        mvc.perform(
            post("/api/management/consultant-memberships")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"institutionId":"institution-1","requestNote":""}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
    }

    private fun unifiedView() = InstitutionMembershipRequestView(
        id = "request-1",
        requestType = MembershipRequestType.CONSULTANT,
        applicantId = "consultant-1",
        applicantName = "顾问一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = "JOIN",
        status = "PENDING",
        relationshipStatus = "NONE",
        requestNote = "加入",
        reviewNote = "",
        submittedBy = "consultant-1",
        reviewedBy = null,
        submittedAt = NOW,
        reviewedAt = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 8, 14, 10, 0)
    }
}

@TestConfiguration
@Import(ConsultantMembershipController::class, JwtAuthenticationFilter::class)
class ConsultantMembershipHttpConfig {
    @Bean fun access(): ManagementAccessService = mockk()
    @Bean fun membership(): InstitutionMembershipRequestService = mockk()
    @Bean fun queries(): InstitutionMembershipRequestQueryService = mockk()
    @Bean fun token(): JwtTokenProvider = mockk(relaxed = true)
    @Bean fun users(): UserRepository = mockk(relaxed = true)
}
