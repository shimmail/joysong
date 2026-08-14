package com.joysong.server.identity.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipCandidatePage
import com.joysong.server.identity.service.InstitutionMembershipCandidateService
import com.joysong.server.identity.service.InstitutionMembershipCandidateView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestType
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest
@ContextConfiguration(
    classes = [
        InstitutionMembershipCandidateHttpConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class InstitutionMembershipCandidateControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: ManagementAccessService
    @Autowired lateinit var candidates: InstitutionMembershipCandidateService

    private val actor = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")
    )

    @BeforeEach
    fun resetMocks() = clearMocks(access, candidates)

    @Test
    @WithMockUser(username = "doctor-1")
    fun `candidate endpoint requires explicit type and action and returns its page envelope`() {
        every { access.actor(any()) } returns actor
        every {
            candidates.list(
                actor,
                MembershipRequestType.DOCTOR,
                InstitutionMembershipAction.JOIN,
                "上海",
                5,
                10
            )
        } returns InstitutionMembershipCandidatePage(
            listOf(InstitutionMembershipCandidateView("institution-1", "上海机构")),
            5,
            10,
            false
        )

        mvc.perform(
            get("/api/management/institution-membership-candidates")
                .param("requestType", "DOCTOR")
                .param("action", "JOIN")
                .param("query", "上海")
                .param("offset", "5")
                .param("limit", "10")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].id").value("institution-1"))
            .andExpect(jsonPath("$.data.items[0].name").value("上海机构"))
            .andExpect(jsonPath("$.data.offset").value(5))
            .andExpect(jsonPath("$.data.limit").value(10))
            .andExpect(jsonPath("$.data.hasMore").value(false))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `candidate malformed unknown and invalid parameters are real HTTP 400`() {
        every { access.actor(any()) } returns actor
        every {
            candidates.list(
                actor,
                MembershipRequestType.DOCTOR,
                InstitutionMembershipAction.JOIN,
                "",
                -1,
                20
            )
        } throws IllegalArgumentException("offset不能小于0")
        listOf(
            "/api/management/institution-membership-candidates?action=JOIN",
            "/api/management/institution-membership-candidates?requestType=UNKNOWN&action=JOIN",
            "/api/management/institution-membership-candidates?requestType=DOCTOR&action=UNKNOWN",
            "/api/management/institution-membership-candidates?requestType=DOCTOR&action=JOIN&offset=-1"
        ).forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }
    }

    @Test
    fun `candidate endpoint rejects unauthenticated calls with matching body code`() {
        mvc.perform(
            get("/api/management/institution-membership-candidates")
                .param("requestType", "DOCTOR")
                .param("action", "JOIN")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
    }
}

@TestConfiguration
@Import(InstitutionMembershipCandidateController::class, JwtAuthenticationFilter::class)
class InstitutionMembershipCandidateHttpConfig {
    @Bean fun access(): ManagementAccessService = mockk()
    @Bean fun candidates(): InstitutionMembershipCandidateService = mockk()
    @Bean fun token(): JwtTokenProvider = mockk(relaxed = true)
    @Bean fun users(): UserRepository = mockk(relaxed = true)
}
