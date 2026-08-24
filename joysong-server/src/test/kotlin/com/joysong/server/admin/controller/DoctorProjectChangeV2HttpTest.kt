package com.joysong.server.admin.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.ProjectChangeCompatibilityConfiguration
import com.joysong.server.config.ProjectChangeCompatibilityProperties
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.DoctorProjectChangeNotFoundException
import com.joysong.server.institution.service.DoctorProjectChangeConflictException
import com.joysong.server.institution.service.DoctorProjectChangeService
import com.joysong.server.institution.service.DoctorProjectChangeView
import com.joysong.server.institution.service.DoctorProjectProfileUpdateTargetV2
import com.joysong.server.institution.service.DoctorProjectReviewV2Command
import com.joysong.server.institution.service.LegacyDoctorProjectChangeViewV2
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeDecision
import com.joysong.server.institution.service.ProjectChangeErrorCode
import com.joysong.server.institution.service.VersionedDoctorProjectChangeViewV2
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.File

class DoctorProjectChangeV2HttpTest {
    @Test
    fun `v2 profile targets match the shared golden shape exactly`() {
        val fixture = fixture()
        every { fixture.service.listProfileUpdateTargetsV2(fixture.actor) } returns listOf(targetV2())

        val result = fixture.mvc.perform(get("$basePath/profile-update-targets").authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andReturn()

        val response = mapper.readTree(result.response.contentAsString)
        assertEquals(setOf("code", "message", "data"), response.fieldNames().asSequence().toSet())
        assertEquals(mapper.createArrayNode().add(golden.path("targetV2")), response.path("data"))
    }

    @Test
    fun `v2 list returns the golden v1 adapter valid v2 and damaged v2 shapes exactly`() {
        val fixture = fixture()
        every { fixture.service.listV2(fixture.actor) } returns listOf(legacyV1(), requestV2(), damagedV2())

        val result = fixture.mvc.perform(get(basePath).authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andReturn()

        val response = mapper.readTree(result.response.contentAsString)
        val expected = mapper.createArrayNode()
            .add(golden.path("requestV1"))
            .add(golden.path("requestV2"))
            .add(golden.path("requestV2InvalidSnapshot"))
        assertEquals(expected, response.path("data"))
    }

    @Test
    fun `v2 join uses the exact five key legacy writer`() {
        val fixture = fixture()
        every { fixture.service.submit(fixture.actor, any()) } returns flatV1View()

        fixture.mvc.perform(post(basePath).json(joinBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())

        verify(exactly = 1) {
            fixture.service.submit(
                fixture.actor,
                match { request ->
                    request.requestType == "JOIN" && request.institutionProjectId == "ip-1" &&
                        request.serviceDescription == "Legacy join" &&
                        request.priceSuggestion?.compareTo(java.math.BigDecimal("1000.00")) == 0 &&
                        request.notes == "Join request"
                }
            )
        }
        verify(exactly = 0) { fixture.service.submitV2(any(), any()) }
    }

    @Test
    fun `v2 leave uses the exact two key legacy writer`() {
        val fixture = fixture()
        every { fixture.service.submit(fixture.actor, any()) } returns flatV1View()

        fixture.mvc.perform(post(basePath).json(leaveBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())

        verify(exactly = 1) {
            fixture.service.submit(
                fixture.actor,
                match { request ->
                    request.requestType == "LEAVE" && request.institutionProjectId == "ip-1"
                }
            )
        }
        verify(exactly = 0) { fixture.service.submitV2(any(), any()) }
    }

    @Test
    fun `v2 profile update uses the full versioned writer`() {
        val fixture = fixture()
        every { fixture.service.submitV2(fixture.actor, any()) } returns requestV2()

        fixture.mvc.perform(post(basePath).json(profileBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data.payloadVersion").value(2))

        verify(exactly = 1) {
            fixture.service.submitV2(
                fixture.actor,
                match { request ->
                    request.requestType == "PROFILE_UPDATE" && request.institutionProjectId == "ip-1" &&
                        request.name == "Updated Face Lift" && request.tags == listOf("local", "updated") &&
                        request.price.compareTo(java.math.BigDecimal("1100.00")) == 0 && request.doctorActive
                }
            )
        }
        verify(exactly = 0) { fixture.service.submit(any(), any()) }
    }

    @Test
    fun `v2 submit rejects every missing or extra key before service conversion`() {
        val fixture = fixture()
        val bodies: List<Pair<Set<String>, ObjectNode>> = listOf(
            joinFields to joinBody(),
            leaveFields to leaveBody(),
            profileFields to profileBody()
        )

        bodies.forEach { (fields, body) ->
            fields.forEach { missing ->
                fixture.mvc.perform(post(basePath).json(body.copyWithout(missing).toString()))
                    .andExpectProjectPayloadInvalid()
            }
            fixture.mvc.perform(post(basePath).json(body.withField("doctorId", "doctor-1").toString()))
                .andExpectProjectPayloadInvalid()
        }

        verify(exactly = 0) { fixture.service.submit(any(), any()) }
        verify(exactly = 0) { fixture.service.submitV2(any(), any()) }
    }

    @Test
    fun `v2 review rejects every missing or extra key before service conversion`() {
        val fixture = fixture()
        val body = ordinaryReviewBody()

        reviewFields.forEach { missing ->
            fixture.mvc.perform(post("$basePath/request-v2/review").json(body.copyWithout(missing).toString()))
                .andExpectProjectPayloadInvalid()
        }
        fixture.mvc.perform(
            post("$basePath/request-v2/review").json(body.withField("unexpected", true).toString())
        ).andExpectProjectPayloadInvalid()

        verify(exactly = 0) { fixture.service.reviewV2(any(), any(), any()) }
    }

    @Test
    fun `v2 ordinary review sends force false and null revision`() {
        val fixture = fixture()
        every { fixture.service.reviewV2(fixture.actor, "request-v2", any()) } returns requestV2()

        fixture.mvc.perform(post("$basePath/request-v2/review").json(ordinaryReviewBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payloadVersion").value(2))

        verify(exactly = 1) {
            fixture.service.reviewV2(
                fixture.actor,
                "request-v2",
                DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
            )
        }
    }

    @Test
    fun `v2 forced approval forwards the audit note and latest revision`() {
        val fixture = fixture()
        every { fixture.service.reviewV2(fixture.actor, "request-v2", any()) } returns requestV2()

        fixture.mvc.perform(post("$basePath/request-v2/review").json(forcedReviewBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payloadVersion").value(2))

        verify(exactly = 1) {
            fixture.service.reviewV2(
                fixture.actor,
                "request-v2",
                DoctorProjectReviewV2Command(
                    ProjectChangeDecision.APPROVED,
                    "override drift",
                    true,
                    revision
                )
            )
        }
    }

    @Test
    fun `v2 route reviews legacy requests through the mixed service boundary`() {
        val fixture = fixture()
        every { fixture.service.reviewV2(fixture.actor, "request-v1", any()) } returns legacyV1()

        fixture.mvc.perform(post("$basePath/request-v1/review").json(ordinaryReviewBody().toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payloadVersion").value(1))

        verify(exactly = 1) { fixture.service.reviewV2(fixture.actor, "request-v1", any()) }
    }

    @Test
    fun `v2 rejects force for non approved decisions before service invocation`() {
        val fixture = fixture()
        val body = forcedReviewBody().put("decision", "REJECTED")

        fixture.mvc.perform(post("$basePath/request-v2/review").json(body.toString()))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.code").value(422))
            .andExpect(jsonPath("$.errorCode").value("FORCE_NOT_APPLICABLE"))
            .andExpect(jsonPath("$.data").value(nullValue()))

        verify(exactly = 0) { fixture.service.reviewV2(any(), any(), any()) }
    }

    @Test
    fun `v2 forced review requires nonblank note and revision`() {
        val fixture = fixture()
        val invalidBodies = listOf(
            forcedReviewBody().put("reviewNote", " "),
            forcedReviewBody().put("forceBaseRevision", " ")
        )

        invalidBodies.forEach { body ->
            fixture.mvc.perform(post("$basePath/request-v2/review").json(body.toString()))
                .andExpectProjectPayloadInvalid()
        }
        verify(exactly = 0) { fixture.service.reviewV2(any(), any(), any()) }
    }

    @Test
    fun `v2 ordinary review rejects a force base revision`() {
        val fixture = fixture()
        val body = ordinaryReviewBody().put("forceBaseRevision", revision)

        fixture.mvc.perform(post("$basePath/request-v2/review").json(body.toString()))
            .andExpectProjectPayloadInvalid()

        verify(exactly = 0) { fixture.service.reviewV2(any(), any(), any()) }
    }

    @Test
    fun `v2 submit and review reject wrong JSON types and unknown discriminators`() {
        val fixture = fixture()
        val invalidRequests = listOf(
            basePath to """{"requestType":7,"institutionProjectId":"ip-1"}""",
            basePath to """{"requestType":"UNKNOWN","institutionProjectId":"ip-1"}""",
            basePath to """{"requestType":"JOIN","institutionProjectId":"ip-1","serviceDescription":"Legacy join","priceSuggestion":"1000","notes":"Join request"}""",
            basePath to profileBody().put("doctorActive", "true").toString(),
            "$basePath/request-v2/review" to """{"decision":7,"reviewNote":"","force":false,"forceBaseRevision":null}""",
            "$basePath/request-v2/review" to """{"decision":"UNKNOWN","reviewNote":"","force":false,"forceBaseRevision":null}""",
            "$basePath/request-v2/review" to """{"decision":"APPROVED","reviewNote":"","force":"false","forceBaseRevision":null}"""
        )

        invalidRequests.forEach { (path, body) ->
            fixture.mvc.perform(post(path).json(body)).andExpectProjectPayloadInvalid()
        }
        verify(exactly = 0) { fixture.service.submit(any(), any()) }
        verify(exactly = 0) { fixture.service.submitV2(any(), any()) }
        verify(exactly = 0) { fixture.service.reviewV2(any(), any(), any()) }
    }

    @Test
    fun `v2 withdraw handles both payload versions through the mixed service boundary`() {
        val fixture = fixture()
        every { fixture.service.withdrawV2(fixture.actor, "request-v1") } returns legacyV1()
        every { fixture.service.withdrawV2(fixture.actor, "request-v2") } returns requestV2()

        fixture.mvc.perform(post("$basePath/request-v1/withdraw").authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payloadVersion").value(1))
        fixture.mvc.perform(post("$basePath/request-v2/withdraw").authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.payloadVersion").value(2))

        verify(exactly = 1) { fixture.service.withdrawV2(fixture.actor, "request-v1") }
        verify(exactly = 1) { fixture.service.withdrawV2(fixture.actor, "request-v2") }
    }

    @Test
    fun `invalid effective profile payload is HTTP 422 with stable error code`() {
        val fixture = fixture()
        every { fixture.service.submitV2(fixture.actor, any()) } throws contractError(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID
        )

        fixture.mvc.perform(post(basePath).json(profileBody().toString()))
            .andExpectProjectPayloadInvalid()
    }

    @Test
    fun `v2 profile semantic IllegalArgumentException is HTTP 422 payload invalid`() {
        val fixture = fixture()
        every {
            fixture.service.submitV2(
                fixture.actor,
                match { request ->
                    request.institutionProjectId.isBlank() || request.baseRevision.length != revision.length ||
                        request.price.signum() <= 0 || request.notes.isBlank()
                }
            )
        } throws IllegalArgumentException("项目资料语义不合法")
        val invalidBodies = listOf(
            profileBody().put("institutionProjectId", ""),
            profileBody().put("baseRevision", "invalid-revision"),
            profileBody().put("price", -1),
            profileBody().put("notes", " ")
        )

        invalidBodies.forEach { body ->
            fixture.mvc.perform(post(basePath).json(body.toString()))
                .andAssertSemanticPayloadInvalid()
        }

        verify(exactly = invalidBodies.size) { fixture.service.submitV2(fixture.actor, any()) }
    }

    @Test
    fun `v2 legacy semantic IllegalArgumentException is HTTP 422 payload invalid`() {
        val fixture = fixture()
        every {
            fixture.service.submit(
                fixture.actor,
                match { request ->
                    val invalidPrice = request.priceSuggestion?.signum()?.let { it <= 0 } ?: false
                    request.institutionProjectId.isBlank() || invalidPrice ||
                        (request.requestType == "JOIN" && request.notes.isBlank())
                }
            )
        } throws IllegalArgumentException("旧版项目申请语义不合法")
        val invalidBodies = listOf(
            joinBody().put("institutionProjectId", ""),
            joinBody().put("priceSuggestion", -1),
            joinBody().put("notes", " "),
            leaveBody().put("institutionProjectId", "")
        )

        invalidBodies.forEach { body ->
            fixture.mvc.perform(post(basePath).json(body.toString()))
                .andAssertSemanticPayloadInvalid()
        }

        verify(exactly = invalidBodies.size) { fixture.service.submit(fixture.actor, any()) }
    }

    @Test
    fun `damaged or unknown snapshot review is HTTP 422 with stable error code`() {
        listOf("request-v2-invalid", "request-v2-unknown-schema").forEach { id ->
            val fixture = fixture()
            every { fixture.service.reviewV2(fixture.actor, id, any()) } throws contractError(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID
            )

            fixture.mvc.perform(post("$basePath/$id/review").json(ordinaryReviewBody().toString()))
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("REQUEST_SNAPSHOT_INVALID"))
                .andExpect(jsonPath("$.data").value(nullValue()))
        }
    }

    @Test
    fun `revoked review permission is HTTP 403 without an error code`() {
        val fixture = fixture()
        every { fixture.service.reviewV2(fixture.actor, "request-v2", any()) } throws
            AccessDeniedException("审核权限已撤销")

        fixture.mvc.perform(post("$basePath/request-v2/review").json(ordinaryReviewBody().toString()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `missing review request is HTTP 404 without an error code`() {
        val fixture = fixture()
        every { fixture.service.reviewV2(fixture.actor, "missing", any()) } throws
            DoctorProjectChangeNotFoundException("项目申请不存在")

        fixture.mvc.perform(post("$basePath/missing/review").json(ordinaryReviewBody().toString()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `stale profile submission is HTTP 409 with stable error code`() {
        val fixture = fixture()
        every { fixture.service.submitV2(fixture.actor, any()) } throws contractError(
            HttpStatus.CONFLICT,
            ProjectChangeErrorCode.EDIT_BASE_STALE
        )

        fixture.mvc.perform(post(basePath).json(profileBody().toString()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.errorCode").value("EDIT_BASE_STALE"))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `v2 legacy duplicate pending submission exposes its stable error code`() {
        val fixture = fixture()
        every { fixture.service.submit(fixture.actor, any()) } throws
            DoctorProjectChangeConflictException("该项目已有待处理申请")

        fixture.mvc.perform(post(basePath).json(joinBody().toString()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.errorCode").value("REQUEST_ALREADY_PENDING"))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    private fun fixture(): Fixture {
        val service = mockk<DoctorProjectChangeService>()
        val access = mockk<ManagementAccessService>()
        val actor = ManagementActor(
            userId = "doctor-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR"),
            doctorId = "doctor-1",
            managedInstitutionIds = setOf("inst-1"),
            doctorInstitutionIds = setOf("inst-1"),
            manageableDoctorIds = emptySet(),
            consultantInstitutionIds = emptySet()
        )
        every { access.actor(any()) } returns actor
        val controller = DoctorProjectChangeV2Controller(service, access, mapper)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .build()
        return Fixture(mvc, service, actor)
    }

    private fun ResultActions.andExpectProjectPayloadInvalid(): ResultActions = this
        .andExpect(status().isUnprocessableEntity)
        .andExpect(jsonPath("$.code").value(422))
        .andExpect(jsonPath("$.errorCode").value("PROJECT_PAYLOAD_INVALID"))
        .andExpect(jsonPath("$.data").value(nullValue()))

    private fun ResultActions.andAssertSemanticPayloadInvalid(): ResultActions = also {
        val response = andReturn().response
        val envelope = mapper.readTree(response.contentAsString)
        assertEquals(
            422 to "PROJECT_PAYLOAD_INVALID",
            response.status to envelope.get("errorCode")?.textValue()
        )
        assertEquals(true, envelope.path("data").isNull)
    }

    private fun MockHttpServletRequestBuilder.authenticated(): MockHttpServletRequestBuilder =
        principal(UsernamePasswordAuthenticationToken("doctor-1", "", emptyList()))

    private fun MockHttpServletRequestBuilder.json(body: String): MockHttpServletRequestBuilder =
        authenticated().contentType(MediaType.APPLICATION_JSON).content(body)

    private fun ObjectNode.copyWithout(field: String): ObjectNode = deepCopy().also { it.remove(field) }

    private fun ObjectNode.withField(field: String, value: String): ObjectNode = deepCopy().put(field, value)

    private fun ObjectNode.withField(field: String, value: Boolean): ObjectNode = deepCopy().put(field, value)

    private data class Fixture(
        val mvc: MockMvc,
        val service: DoctorProjectChangeService,
        val actor: ManagementActor
    )

    private companion object {
        const val basePath = "/api/v2/admin/institution-project-requests"
        const val revision = "314a4110fbe7a30bbd69054b85e25fd0f6b5c646dce0f5df35d1f2913ed635f1"
        val joinFields = setOf(
            "requestType", "institutionProjectId", "serviceDescription", "priceSuggestion", "notes"
        )
        val leaveFields = setOf("requestType", "institutionProjectId")
        val profileFields = setOf(
            "requestType", "institutionProjectId", "baseRevision", "name", "category", "description", "tags",
            "slogan", "detailContent", "price", "salesCount", "doctorActive", "coverImage", "images", "notes"
        )
        val reviewFields = setOf("decision", "reviewNote", "force", "forceBaseRevision")
        val mapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val golden = mapper.readTree(File("../test-fixtures/doctor-project-change-v2.json"))

        fun targetV2(): DoctorProjectProfileUpdateTargetV2 =
            mapper.treeToValue(golden.path("targetV2"), DoctorProjectProfileUpdateTargetV2::class.java)

        fun legacyV1(): LegacyDoctorProjectChangeViewV2 =
            mapper.treeToValue(golden.path("requestV1"), LegacyDoctorProjectChangeViewV2::class.java)

        fun requestV2(): VersionedDoctorProjectChangeViewV2 =
            mapper.treeToValue(golden.path("requestV2"), VersionedDoctorProjectChangeViewV2::class.java)

        fun damagedV2(): VersionedDoctorProjectChangeViewV2 =
            mapper.treeToValue(golden.path("requestV2InvalidSnapshot"), VersionedDoctorProjectChangeViewV2::class.java)

        fun flatV1View(): DoctorProjectChangeView {
            val node = golden.path("requestV1").deepCopy<ObjectNode>().also { it.remove("payloadVersion") }
            return mapper.treeToValue(node, DoctorProjectChangeView::class.java)
        }

        fun joinBody(): ObjectNode = mapper.readTree(
            """{"requestType":"JOIN","institutionProjectId":"ip-1","serviceDescription":"Legacy join","priceSuggestion":1000.00,"notes":"Join request"}"""
        ) as ObjectNode

        fun leaveBody(): ObjectNode = mapper.readTree(
            """{"requestType":"LEAVE","institutionProjectId":"ip-1"}"""
        ) as ObjectNode

        fun profileBody(): ObjectNode = mapper.readTree(
            """{
                "requestType":"PROFILE_UPDATE",
                "institutionProjectId":"ip-1",
                "baseRevision":"$revision",
                "name":"Updated Face Lift",
                "category":null,
                "description":null,
                "tags":["local","updated"],
                "slogan":null,
                "detailContent":null,
                "price":1100.00,
                "salesCount":12,
                "doctorActive":true,
                "coverImage":null,
                "images":["https://cdn.example/local.jpg"],
                "notes":"Update local details"
            }"""
        ) as ObjectNode

        fun ordinaryReviewBody(): ObjectNode = mapper.readTree(
            """{"decision":"APPROVED","reviewNote":"","force":false,"forceBaseRevision":null}"""
        ) as ObjectNode

        fun forcedReviewBody(): ObjectNode = mapper.readTree(
            """{"decision":"APPROVED","reviewNote":"override drift","force":true,"forceBaseRevision":"$revision"}"""
        ) as ObjectNode

        fun contractError(status: HttpStatus, code: ProjectChangeErrorCode) =
            ProjectChangeContractException(status, code, code.name)
    }
}

@WebMvcTest
@ContextConfiguration(
    classes = [
        DoctorProjectChangeV2SecurityTestConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class DoctorProjectChangeV2SecurityTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var accessService: ManagementAccessService

    @Autowired
    private lateinit var projectChangeService: DoctorProjectChangeService

    private val actor = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf("inst-1"),
        manageableDoctorIds = emptySet(),
        consultantInstitutionIds = emptySet()
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(accessService, projectChangeService)
    }

    @Test
    fun `unauthenticated v2 base route returns HTTP 401`() {
        mockMvc.perform(get(basePath))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    fun `unauthenticated v2 child route returns HTTP 401`() {
        mockMvc.perform(post("$basePath/request-v2/withdraw"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    @WithMockUser(username = "doctor-1", roles = ["USER"])
    fun `authenticated non admin user reaches the v2 controller`() {
        every { accessService.actor(any()) } returns actor
        every { projectChangeService.listV2(actor) } returns emptyList()

        mockMvc.perform(get(basePath))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray)
    }

    private companion object {
        const val basePath = "/api/v2/admin/institution-project-requests"
    }
}

@TestConfiguration
@Import(DoctorProjectChangeV2Controller::class, JwtAuthenticationFilter::class)
class DoctorProjectChangeV2SecurityTestConfig {
    @Bean
    fun managementAccessService(): ManagementAccessService = mockk()

    @Bean
    fun doctorProjectChangeService(): DoctorProjectChangeService = mockk()

    @Bean
    fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

    @Bean
    fun userRepository(): UserRepository = mockk(relaxed = true)
}

class ProjectChangeCompatibilityPropertiesContextTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ProjectChangeCompatibilityConfiguration::class.java)

    @Test
    fun `compatibility properties are registered with v1 profile updates enabled by default`() {
        contextRunner.run { context ->
            assertEquals(
                true,
                context.getBean(ProjectChangeCompatibilityProperties::class.java).v1ProfileUpdateEnabled
            )
        }
    }

    @Test
    fun `compatibility properties bind the v1 profile update override`() {
        contextRunner
            .withPropertyValues("app.project-change.v1-profile-update-enabled=false")
            .run { context ->
                assertEquals(
                    false,
                    context.getBean(ProjectChangeCompatibilityProperties::class.java).v1ProfileUpdateEnabled
                )
            }
    }
}
