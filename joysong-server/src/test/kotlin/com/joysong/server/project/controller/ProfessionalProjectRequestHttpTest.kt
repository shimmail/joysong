package com.joysong.server.project.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.project.service.DoctorInstitutionProjectRequest
import com.joysong.server.project.service.DoctorPlatformProjectRequest
import com.joysong.server.project.service.ProfessionalProjectRequestService
import com.joysong.server.project.service.ProfessionalProjectRequestConflictException
import com.joysong.server.project.service.ProfessionalProjectRequestNotFoundException
import com.joysong.server.project.service.ProjectRequestReviewResult
import com.joysong.server.project.service.ProjectRequestSubmissionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

class ProfessionalProjectRequestHttpTest {
    @Test
    fun `platform application accepts exactly the complete platform shape`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } returns ProjectRequestSubmissionResult("request-1", "PLATFORM", "PENDING")

        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestType").value("PLATFORM"))

        verify(exactly = 1) { fixture.service.submitPlatform(fixture.actor, any()) }
    }

    @Test
    fun `institution application accepts exactly the complete institution shape with institution only in path`() {
        val fixture = fixture()
        every { fixture.service.submitInstitution(any(), any(), any()) } returns ProjectRequestSubmissionResult("request-2", "INSTITUTION", "PENDING")

        fixture.mvc.perform(post("/api/management/project-requests/institutions/institution-1").json(institutionBody()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.requestType").value("INSTITUTION"))

        verify(exactly = 1) { fixture.service.submitInstitution(fixture.actor, "institution-1", any()) }
    }

    @Test
    fun `platform application rejects each missing documented key before service invocation`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected", "PLATFORM", "PENDING")

        platformRequestFields.forEach { missingField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/platform")
                    .json(withoutField(platformBody(), missingField))
            ).andExpect(status().isBadRequest)
        }

        verify(exactly = 0) { fixture.service.submitPlatform(any(), any()) }
    }

    @Test
    fun `institution application rejects each missing documented key before service invocation`() {
        val fixture = fixture()
        every { fixture.service.submitInstitution(any(), any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected", "INSTITUTION", "PENDING")

        institutionRequestFields.forEach { missingField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/institutions/institution-1")
                    .json(withoutField(institutionBody(), missingField))
            ).andExpect(status().isBadRequest)
        }

        verify(exactly = 0) { fixture.service.submitInstitution(any(), any(), any()) }
    }

    @Test
    fun `platform application rejects explicit null for every non nullable field before service invocation`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected", "PLATFORM", "PENDING")

        platformNonNullableFields.forEach { nullField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/platform")
                    .json(withField(platformBody(), nullField, "null"))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }

        verify(exactly = 0) { fixture.service.submitPlatform(any(), any()) }
    }

    @Test
    fun `institution application rejects explicit null for every non nullable field before service invocation`() {
        val fixture = fixture()
        every { fixture.service.submitInstitution(any(), any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected", "INSTITUTION", "PENDING")

        institutionNonNullableFields.forEach { nullField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/institutions/institution-1")
                    .json(withField(institutionBody(), nullField, "null"))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }

        verify(exactly = 0) { fixture.service.submitInstitution(any(), any(), any()) }
    }

    @Test
    fun `application payloads reject malformed enum number boolean string and array values before service invocation`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected-platform", "PLATFORM", "PENDING")
        every { fixture.service.submitInstitution(any(), any(), any()) } returns
            ProjectRequestSubmissionResult("unexpected-institution", "INSTITUTION", "PENDING")
        val malformedPlatformFields = mapOf(
            "salesCount" to "false",
            "currency" to "\"EUR\"",
            "referencePrice" to "{}",
            "images" to "[\"one.png\",null]",
            "tags" to "[\"skin\",5]",
            "categoryTags" to "{}"
        )
        val malformedInstitutionFields = mapOf(
            "isActive" to "0",
            "projectId" to "123",
            "currency" to "\"EUR\"",
            "price" to "[]",
            "salesCount" to "\"five\"",
            "consultationFee" to "true",
            "commissionRate" to "\"twenty\"",
            "institutionRate" to "{}",
            "images" to "[null]",
            "tags" to "[{}]"
        )

        malformedPlatformFields.forEach { (field, value) ->
            fixture.mvc.perform(
                post("/api/management/project-requests/platform")
                    .json(withField(platformBody(), field, value))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }
        malformedInstitutionFields.forEach { (field, value) ->
            fixture.mvc.perform(
                post("/api/management/project-requests/institutions/institution-1")
                    .json(withField(institutionBody(), field, value))
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
        }

        verify(exactly = 0) { fixture.service.submitPlatform(any(), any()) }
        verify(exactly = 0) { fixture.service.submitInstitution(any(), any(), any()) }
    }

    @Test
    fun `application payloads continue to accept explicit null for nullable fields`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } returns
            ProjectRequestSubmissionResult("nullable-platform", "PLATFORM", "PENDING")
        every { fixture.service.submitInstitution(any(), any(), any()) } returns
            ProjectRequestSubmissionResult("nullable-institution", "INSTITUTION", "PENDING")

        platformNullableFields.forEach { nullableField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/platform")
                    .json(withField(platformBody(), nullableField, "null"))
            ).andExpect(status().isOk)
        }
        institutionNullableFields.forEach { nullableField ->
            fixture.mvc.perform(
                post("/api/management/project-requests/institutions/institution-1")
                    .json(withField(institutionBody(), nullableField, "null"))
            ).andExpect(status().isOk)
        }

        verify(exactly = platformNullableFields.size) { fixture.service.submitPlatform(fixture.actor, any()) }
        verify(exactly = institutionNullableFields.size) {
            fixture.service.submitInstitution(fixture.actor, "institution-1", any())
        }
    }

    @Test
    fun `application payloads reject arbitrary and prohibited fields before service invocation`() {
        val fixture = fixture()

        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody("\"unexpected\":true")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody("\"unknownFields\":{}")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody("\"capturedUnsupportedFields\":{}")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody("\"institutionId\":\"body-institution\"")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/institutions/institution-1").json(institutionBody("\"institutionId\":\"body-institution\"")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/institutions/institution-1").json(institutionBody("\"unknownFields\":{}")))
            .andExpect(status().isBadRequest)
        fixture.mvc.perform(post("/api/management/project-requests/institutions/institution-1").json(institutionBody("\"capturedUnsupportedFields\":{}")))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { fixture.service.submitPlatform(any(), any()) }
        verify(exactly = 0) { fixture.service.submitInstitution(any(), any(), any()) }
    }

    @Test
    fun `institution form configuration exposes only policy platform rate to an active doctor`() {
        val fixture = fixture()

        val response = fixture.mvc.perform(get("/api/management/project-requests/institution-form-config").principal(fixture.authentication))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.platformRate").value(17.5))
            .andExpect(jsonPath("$.data").isMap)
            .andExpect(jsonPath("$.data.institutionRate").doesNotExist())
            .andReturn()

        assertEquals(1, productionObjectMapper.readTree(response.response.contentAsString).path("data").size())
    }

    @Test
    fun `institution form configuration rejects non-doctors`() {
        val fixture = fixture(actor = ManagementActor("user-2", false, emptySet(), null, emptySet(), emptySet(), emptySet()))

        fixture.mvc.perform(get("/api/management/project-requests/institution-form-config").principal(fixture.authentication))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    fun `institution form configuration rejects a doctor id without an active doctor role`() {
        val fixture = fixture(actor = ManagementActor("doctor-1", false, emptySet(), "doctor-1", emptySet(), emptySet(), setOf("doctor-1")))

        fixture.mvc.perform(get("/api/management/project-requests/institution-form-config").principal(fixture.authentication))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    fun `project request endpoints reject missing principal as HTTP forbidden`() {
        val fixture = fixture()

        fixture.mvc.perform(
            post("/api/management/project-requests/platform")
                .contentType(MediaType.APPLICATION_JSON)
                .content(platformBody())
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    fun `application request VOs expose exactly their documented writable JSON keys`() {
        assertEquals(
            setOf(
                "name", "category", "description", "referencePrice", "currency", "slogan", "salesCount",
                "coverImage", "images", "detailContent", "tags", "categoryTags", "notes"
            ),
            writableProperties(DoctorPlatformProjectRequest::class.java)
        )
        assertEquals(
            setOf(
                "projectId", "name", "category", "description", "tags", "slogan", "detailContent", "price",
                "originalPrice", "currency", "coverImage", "images", "salesCount", "isActive", "consultationFee",
                "commissionRate", "institutionRate", "notes"
            ),
            writableProperties(DoctorInstitutionProjectRequest::class.java)
        )
    }

    @Test
    fun `review accepts approved and rejected but rejects changes requested and blank rejection note`() {
        val fixture = fixture()
        every { fixture.service.reviewInstitution(any(), any(), any()) } returns ProjectRequestReviewResult("request-1", "APPROVED", null, null)

        fixture.mvc.perform(post("/api/management/project-requests/request-1/review").json("""{"decision":"APPROVED"}"""))
            .andExpect(status().isOk)
        fixture.mvc.perform(post("/api/management/project-requests/request-1/review").json("""{"decision":"REJECTED","reviewNote":"duplicate"}"""))
            .andExpect(status().isOk)
        fixture.mvc.perform(post("/api/management/project-requests/request-1/review").json("""{"decision":"CHANGES_REQUESTED","reviewNote":"revise"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
        fixture.mvc.perform(post("/api/management/project-requests/request-1/review").json("""{"decision":"REJECTED","reviewNote":" "}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    @Test
    fun `admin project review rejects changes requested at the API boundary`() {
        val access = mockk<ManagementAccessService>()
        val service = mockk<ProfessionalProjectRequestService>()
        val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
        every { access.actor(any()) } returns actor
        every { service.reviewPlatform(any(), any(), any()) } returns ProjectRequestReviewResult("request-1", "CHANGES_REQUESTED", null, null)
        val mvc = MockMvcBuilders.standaloneSetup(AdminProfessionalProjectRequestController(service, access))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mvc.perform(post("/api/admin/project-requests/request-1/review").json("""{"decision":"CHANGES_REQUESTED","reviewNote":"revise"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    @Test
    fun `access denial is exposed as HTTP forbidden for project request routes`() {
        val fixture = fixture()
        every { fixture.service.submitPlatform(any(), any()) } throws AccessDeniedException("无权提交")

        fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    fun `institution review exposes wrong target institution authority as HTTP forbidden`() {
        val fixture = fixture()
        every { fixture.service.reviewInstitution(fixture.actor, "request-1", any()) } throws AccessDeniedException("只能审核本机构的项目申请")

        fixture.mvc.perform(post("/api/management/project-requests/request-1/review").json("""{"decision":"APPROVED"}"""))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))

        verify(exactly = 1) { fixture.service.reviewInstitution(fixture.actor, "request-1", any()) }
        verify(exactly = 0) { fixture.service.reviewPlatform(any(), any(), any()) }
    }

    @Test
    fun `missing project request institution or project is exposed as HTTP not found`() {
        listOf("项目申请不存在", "机构不存在", "平台项目不存在").forEach { message ->
            val fixture = fixture()
            every { fixture.service.submitInstitution(any(), any(), any()) } throws ProfessionalProjectRequestNotFoundException(message)

            fixture.mvc.perform(post("/api/management/project-requests/institutions/institution-1").json(institutionBody()))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value(404))
        }
    }

    @Test
    fun `project request conflicts are exposed as HTTP conflict`() {
        listOf(
            "同一项目已有待处理申请",
            "项目申请已处理",
            "医生已不具备该机构的有效执业关系",
            "该机构已存在此平台项目",
            "平台分账比例已变更"
        ).forEach { message ->
            val fixture = fixture()
            every { fixture.service.submitPlatform(any(), any()) } throws ProfessionalProjectRequestConflictException(message)

            fixture.mvc.perform(post("/api/management/project-requests/platform").json(platformBody()))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(409))
        }
    }

    @Test
    fun `project request legacy sibling keeps legacy HTTP status behavior`() {
        val mvc = MockMvcBuilders.standaloneSetup(LegacyProjectRequestsController())
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        mvc.perform(get("/api/management/project-requests-legacy"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(400))
    }

    private fun writableProperties(type: Class<*>): Set<String> =
        productionObjectMapper.deserializationConfig
            .introspect(productionObjectMapper.constructType(type))
            .findProperties()
            .filter { it.couldDeserialize() }
            .map { it.name }
            .toSet()

    private fun fixture(actor: ManagementActor = ManagementActor("doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1"))): Fixture {
        val access = mockk<ManagementAccessService>()
        val service = mockk<ProfessionalProjectRequestService>()
        val splitRatePolicy = mockk<OrderSplitRatePolicy>()
        val authentication = UsernamePasswordAuthenticationToken("doctor-1", "", emptyList())
        every { access.actor(any()) } returns actor
        every { splitRatePolicy.currentPlatformRate() } returns java.math.BigDecimal("17.50")
        return Fixture(
            MockMvcBuilders.standaloneSetup(
                ProfessionalProjectRequestController(service, access, splitRatePolicy, productionObjectMapper)
            )
                .setControllerAdvice(GlobalExceptionHandler())
                .build(),
            service,
            actor,
            authentication,
            splitRatePolicy
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.json(body: String) =
        contentType(MediaType.APPLICATION_JSON).content(body).principal(UsernamePasswordAuthenticationToken("doctor-1", "", emptyList()))

    private fun platformBody(extra: String = "") = """{
        "name":"Hydra facial","category":"SKIN","description":"Hydrating facial treatment","referencePrice":199.99,"currency":"USD","slogan":"Glow today","salesCount":5,"coverImage":"cover.png","images":["one.png"],"detailContent":"Details","tags":["hydration"],"categoryTags":["skin"],"notes":"launch"${if (extra.isBlank()) "" else ",$extra"}
    }"""

    private fun institutionBody(extra: String = "") = """{
        "projectId":"project-1","name":"Hydra facial","category":"SKIN","description":"Hydrating facial treatment","tags":["hydration"],"slogan":"Glow today","detailContent":"Details","price":199.99,"originalPrice":249.99,"currency":"USD","coverImage":"cover.png","images":["one.png"],"salesCount":5,"isActive":true,"consultationFee":10.00,"commissionRate":20.00,"institutionRate":30.00,"notes":"launch"${if (extra.isBlank()) "" else ",$extra"}
    }"""

    private fun withoutField(body: String, field: String): String =
        (productionObjectMapper.readTree(body) as ObjectNode)
            .deepCopy()
            .also { it.remove(field) }
            .toString()

    private fun withField(body: String, field: String, value: String): String =
        (productionObjectMapper.readTree(body) as ObjectNode)
            .deepCopy()
            .also { it.set<com.fasterxml.jackson.databind.JsonNode>(field, productionObjectMapper.readTree(value)) }
            .toString()

    private data class Fixture(
        val mvc: org.springframework.test.web.servlet.MockMvc,
        val service: ProfessionalProjectRequestService,
        val actor: ManagementActor,
        val authentication: UsernamePasswordAuthenticationToken,
        val splitRatePolicy: OrderSplitRatePolicy
    )

    @RestController
    @RequestMapping("/api/management/project-requests-legacy")
    private class LegacyProjectRequestsController {
        @GetMapping
        fun list(): Nothing = throw IllegalArgumentException("legacy request")
    }

    private companion object {
        val productionObjectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()
        val platformRequestFields = listOf(
            "name", "category", "description", "referencePrice", "currency", "slogan", "salesCount",
            "coverImage", "images", "detailContent", "tags", "categoryTags", "notes"
        )
        val institutionRequestFields = listOf(
            "projectId", "name", "category", "description", "tags", "slogan", "detailContent", "price",
            "originalPrice", "currency", "coverImage", "images", "salesCount", "isActive", "consultationFee",
            "commissionRate", "institutionRate", "notes"
        )
        val platformNonNullableFields = listOf(
            "salesCount", "referencePrice", "currency", "name", "category", "description", "slogan",
            "coverImage", "images", "tags", "categoryTags", "notes"
        )
        val institutionNonNullableFields = listOf(
            "isActive", "price", "consultationFee", "commissionRate", "institutionRate", "salesCount",
            "currency", "projectId", "notes"
        )
        val platformNullableFields = listOf("detailContent")
        val institutionNullableFields = listOf(
            "name", "category", "description", "tags", "slogan", "detailContent", "originalPrice",
            "coverImage", "images"
        )
    }
}
