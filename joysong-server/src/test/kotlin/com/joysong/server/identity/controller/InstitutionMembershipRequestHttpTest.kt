package com.joysong.server.identity.controller

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.ConsultantInstitutionChangeRequestService
import com.joysong.server.identity.service.DoctorInstitutionAction
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestService
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestStore
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestView
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.identity.service.DoctorInstitutionRequestConflictException
import com.joysong.server.identity.service.DoctorInstitutionRequestStatus
import com.joysong.server.identity.service.InstitutionMembershipRequestConflictException
import com.joysong.server.identity.service.InstitutionMembershipRequestNotFoundException
import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.MembershipRequestType
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest
@ContextConfiguration(
    classes = [
        InstitutionMembershipRequestHttpConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class InstitutionMembershipRequestHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var access: ManagementAccessService
    @Autowired lateinit var mutations: InstitutionMembershipRequestService
    @Autowired lateinit var queries: InstitutionMembershipRequestQueryService

    private val actor = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")
    )
    private val legal = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("institution-1"), emptySet(), emptySet()
    )

    @BeforeEach
    fun resetMocks() = clearMocks(access, mutations, queries)

    @Test
    fun `unauthenticated generic workflow is HTTP 401 with matching body code`() {
        mvc.perform(get("/api/management/institution-membership-requests/owned"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `malformed unknown missing and invalid mutation fields are HTTP 400`() {
        every { access.actor(any()) } returns actor
        listOf(
            """{"requestType":"DOCTOR","institutionId":"institution-1"}""",
            """{"requestType":"DOCTOR","institutionId":"institution-1","action":"JOIN","extra":true}""",
            """{"requestType":"UNKNOWN","institutionId":"institution-1","action":"JOIN"}""",
            """{"requestType":"DOCTOR","institutionId":"institution-1","action":"INVALID"}""",
            """{"requestType":"DOCTOR"""
        ).forEach { body ->
            mvc.perform(
                post("/api/management/institution-membership-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `inactive identity cross owner withdraw and cross institution review are HTTP 403`() {
        every { access.actor(any()) } throws AccessDeniedException("账号尚未取得专业身份管理权限")
        mvc.perform(
            post("/api/management/institution-membership-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"requestType":"DOCTOR","institutionId":"institution-1","action":"JOIN"}"""
                )
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))

        every { access.actor(any()) } returns legal
        every { mutations.review(any(), any(), any(), any(), any()) } throws
            AccessDeniedException("无权审核其他机构的关系申请")
        mvc.perform(
            post("/api/management/institution-membership-requests/CONSULTANT/request-1/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))

        every { mutations.withdraw(any(), any(), any()) } throws
            AccessDeniedException("只能撤回本人提交的关系申请")
        mvc.perform(
            post("/api/management/institution-membership-requests/CONSULTANT/other-request/withdraw")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `missing request and institution are HTTP 404`() {
        every { access.actor(any()) } returns actor
        every { mutations.withdraw(any(), any(), any()) } throws
            InstitutionMembershipRequestNotFoundException("机构关系申请不存在")
        mvc.perform(
            post("/api/management/institution-membership-requests/DOCTOR/missing/withdraw")
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))

        every { mutations.submit(any(), any(), any(), any(), any()) } throws
            InstitutionMembershipRequestNotFoundException("机构不存在")
        mvc.perform(
            post("/api/management/institution-membership-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"requestType":"DOCTOR","institutionId":"missing","action":"JOIN"}"""
                )
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `duplicate pending and lost concurrent update are HTTP 409`() {
        every { access.actor(any()) } returns actor
        every { mutations.submit(any(), any(), any(), any(), any()) } throws
            InstitutionMembershipRequestConflictException("该机构已有待处理的关系申请")
        mvc.perform(
            post("/api/management/institution-membership-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"requestType":"DOCTOR","institutionId":"institution-1","action":"JOIN"}"""
                )
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))

        every { mutations.review(any(), any(), any(), any(), any()) } throws
            InstitutionMembershipRequestConflictException("关系申请已被其他操作处理")
        mvc.perform(
            post("/api/management/institution-membership-requests/DOCTOR/request-1/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `legacy root JSON remains decodable by the old Flutter required fields`() {
        every { access.actor(any()) } returns actor
        every { queries.listCompatibility(actor) } returns listOf(requestView())
        every { queries.listOwned(actor) } returns listOf(requestView())

        val body = mvc.perform(get("/api/management/institution-membership-requests"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].userId").value("doctor-1"))
            .andExpect(jsonPath("$.data[0].applicantId").value("doctor-1"))
            .andReturn().response.contentAsString
        val legacy = objectMapper.treeToValue(
            objectMapper.readTree(body).path("data").first(),
            LegacyFlutterMembershipRequest::class.java
        )

        assertEquals("doctor-1", legacy.userId)
        assertEquals("DOCTOR", legacy.requestType)
        assertEquals("institution-1", legacy.institutionId)

        mvc.perform(get("/api/management/institution-membership-requests/owned"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].applicantId").value("doctor-1"))
            .andExpect(jsonPath("$.data[0].applicantName").value("医生一"))
            .andExpect(jsonPath("$.data[0].relationshipStatus").value("NONE"))
            .andExpect(jsonPath("$.data[0].userId").doesNotExist())
    }

    private fun requestView() = InstitutionMembershipRequestView(
        id = "request-1",
        requestType = MembershipRequestType.DOCTOR,
        applicantId = "doctor-1",
        applicantName = "医生一",
        institutionId = "institution-1",
        institutionName = "机构一",
        action = "JOIN",
        status = "PENDING",
        relationshipStatus = "NONE",
        requestNote = "",
        reviewNote = "",
        submittedBy = "doctor-1",
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

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LegacyFlutterMembershipRequest(
    val id: String,
    val requestType: String,
    val userId: String,
    val institutionId: String,
    val status: String,
    val requestNote: String = "",
    val reviewNote: String = ""
)

@TestConfiguration
@Import(InstitutionMembershipRequestController::class, JwtAuthenticationFilter::class)
class InstitutionMembershipRequestHttpConfig {
    @Bean fun access(): ManagementAccessService = mockk()
    @Bean fun mutations(): InstitutionMembershipRequestService = mockk()
    @Bean fun queries(): InstitutionMembershipRequestQueryService = mockk()
    @Bean fun token(): JwtTokenProvider = mockk(relaxed = true)
    @Bean fun users(): UserRepository = mockk(relaxed = true)
}

@WebMvcTest
@ContextConfiguration(
    classes = [
        InstitutionMembershipDoctorConflictHttpConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class InstitutionMembershipDoctorConflictHttpTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var access: ManagementAccessService
    @Autowired lateinit var relationships: DoctorInstitutionRelationshipService
    @Autowired lateinit var store: PendingDoctorRequestStore

    private val legal = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
        setOf("institution-1"), emptySet(), emptySet()
    )

    @BeforeEach
    fun resetState() {
        clearMocks(access, relationships)
        store.reset()
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `invalidated doctor approval is HTTP 409 and leaves the request pending`() {
        every { access.actor(any()) } returns legal
        every {
            relationships.approveJoin("doctor-1", "institution-1", "legal-1")
        } throws DoctorInstitutionRequestConflictException("医生身份已失效")

        mvc.perform(
            post("/api/management/institution-membership-requests/DOCTOR/request-1/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"decision":"APPROVED"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value("医生身份已失效"))

        assertEquals(DoctorInstitutionRequestStatus.PENDING, store.request.status)
        assertEquals(0, store.statusChangeCount)
    }
}

@TestConfiguration
@Import(InstitutionMembershipRequestController::class, JwtAuthenticationFilter::class)
class InstitutionMembershipDoctorConflictHttpConfig {
    @Bean fun access(): ManagementAccessService = mockk()
    @Bean fun relationships(): DoctorInstitutionRelationshipService = mockk()
    @Bean fun doctorStore() = PendingDoctorRequestStore()
    @Bean
    fun doctorRequests(
        store: PendingDoctorRequestStore,
        relationships: DoctorInstitutionRelationshipService
    ) = DoctorInstitutionChangeRequestService(store, relationships)
    @Bean fun consultantRequests(): ConsultantInstitutionChangeRequestService = mockk()
    @Bean
    fun mutations(
        doctor: DoctorInstitutionChangeRequestService,
        consultant: ConsultantInstitutionChangeRequestService
    ) = InstitutionMembershipRequestService(doctor, consultant)
    @Bean fun queries(): InstitutionMembershipRequestQueryService = mockk()
    @Bean fun token(): JwtTokenProvider = mockk(relaxed = true)
    @Bean fun users(): UserRepository = mockk(relaxed = true)
}

class PendingDoctorRequestStore : DoctorInstitutionChangeRequestStore {
    lateinit var request: DoctorInstitutionChangeRequestView
        private set
    var statusChangeCount: Int = 0
        private set

    init {
        reset()
    }

    fun reset() {
        val now = LocalDateTime.of(2026, 8, 14, 10, 0)
        request = DoctorInstitutionChangeRequestView(
            "request-1", "doctor-1", "医生一", "institution-1", "机构一",
            DoctorInstitutionAction.JOIN, DoctorInstitutionRequestStatus.PENDING, "", "",
            "doctor-1", null, now, null, now, now
        )
        statusChangeCount = 0
    }

    override fun exists(id: String) = id == request.id
    override fun lock(id: String) = request.takeIf { it.id == id }
    override fun changeStatus(
        id: String,
        status: DoctorInstitutionRequestStatus,
        actorId: String,
        reviewNote: String
    ): Boolean {
        statusChangeCount += 1
        request = request.copy(status = status, reviewedBy = actorId, reviewNote = reviewNote)
        return true
    }

    override fun isCertifiedDoctor(doctorId: String) = error("not used")
    override fun isActiveInstitution(institutionId: String) = error("not used")
    override fun hasActiveRelationship(doctorId: String, institutionId: String) = error("not used")
    override fun hasPending(doctorId: String, institutionId: String) = error("not used")
    override fun create(
        doctorId: String,
        institutionId: String,
        action: DoctorInstitutionAction,
        requestNote: String
    ): DoctorInstitutionChangeRequestView = error("not used")
    override fun listVisible(
        doctorId: String?,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<DoctorInstitutionChangeRequestView> = error("not used")
}
