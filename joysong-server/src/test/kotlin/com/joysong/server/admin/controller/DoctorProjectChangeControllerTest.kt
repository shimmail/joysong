package com.joysong.server.admin.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.ProjectChangeCompatibilityProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.DoctorProjectChangeService
import com.joysong.server.institution.service.DoctorProjectChangeView
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.io.File

class DoctorProjectChangeControllerTest {
    @Test
    fun `v1 profile targets stay on the flat service boundary`() {
        val fixture = fixture()
        every { fixture.service.listProfileUpdateTargets(fixture.actor) } returns emptyList()

        fixture.mvc.perform(get("/api/admin/institution-project-requests/profile-update-targets").authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data").isArray)

        verify(exactly = 1) { fixture.service.listProfileUpdateTargets(fixture.actor) }
        verify(exactly = 0) { fixture.service.listProfileUpdateTargetsV2(any()) }
    }

    @Test
    fun `v1 list stays flat and cannot expose v2 rows`() {
        val fixture = fixture()
        val expected = flatV1Node()
        every { fixture.service.list(fixture.actor) } returns listOf(flatV1View())

        val response = fixture.mvc.perform(get("/api/admin/institution-project-requests").authenticated())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data[0].payloadVersion").doesNotExist())
            .andExpect(jsonPath("$.data[0].currentProject").doesNotExist())
            .andReturn()

        assertEquals(mapper.createArrayNode().add(expected), mapper.readTree(response.response.contentAsString).path("data"))
        verify(exactly = 1) { fixture.service.list(fixture.actor) }
        verify(exactly = 0) { fixture.service.listV2(any()) }
    }

    @Test
    fun `v1 join and leave submissions keep their flat responses`() {
        val fixture = fixture()
        every { fixture.service.submit(fixture.actor, any()) } returns flatV1View()
        val bodies = listOf(
            """{"requestType":"JOIN","institutionProjectId":"ip-1","serviceDescription":"Legacy","priceSuggestion":1000.00,"notes":"Join"}""",
            """{"requestType":"LEAVE","institutionProjectId":"ip-1"}"""
        )

        bodies.forEach { body ->
            fixture.mvc.perform(post("/api/admin/institution-project-requests").json(body))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.errorCode").doesNotExist())
                .andExpect(jsonPath("$.data.payloadVersion").doesNotExist())
        }

        verify(exactly = 2) { fixture.service.submit(fixture.actor, any()) }
    }

    @Test
    fun `v1 profile update remains enabled by default and forwards compatibility price`() {
        val fixture = fixture()
        every { fixture.service.submit(fixture.actor, any()) } returns flatV1View()

        fixture.mvc.perform(post("/api/admin/institution-project-requests").json(legacyProfileBody()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data.payloadVersion").doesNotExist())

        verify(exactly = 1) {
            fixture.service.submit(
                fixture.actor,
                match { request ->
                    request.requestType == "PROFILE_UPDATE" &&
                        request.serviceTags == listOf("legacy") && request.images == listOf("legacy-1.jpg") &&
                        request.medicalListPrice?.compareTo(java.math.BigDecimal("1000.00")) == 0
                }
            )
        }
    }

    @Test
    fun `v1 profile update still rejects doctor supplied platform rate`() {
        val fixture = fixture()
        val body = mapper.readTree(legacyProfileBody()).deepCopy<ObjectNode>().put("platformRate", "99.99")

        fixture.mvc.perform(post("/api/admin/institution-project-requests").json(body.toString()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.errorCode").doesNotExist())

        verify(exactly = 0) { fixture.service.submit(any(), any()) }
    }

    @Test
    fun `v1 profile update returns upgrade required when compatibility window is closed`() {
        val fixture = fixture(v1ProfileUpdateEnabled = false)
        every { fixture.service.submit(any(), any()) } returns flatV1View()

        fixture.mvc.perform(post("/api/admin/institution-project-requests").json(legacyProfileBody()))
            .andExpect(status().`is`(426))
            .andExpect(jsonPath("$.code").value(426))
            .andExpect(jsonPath("$.errorCode").value("CLIENT_UPGRADE_REQUIRED"))
            .andExpect(jsonPath("$.data").value(nullValue()))

        verify(exactly = 0) { fixture.service.submit(any(), any()) }
    }

    @Test
    fun `v1 review keeps the original three keys and flat response`() {
        val fixture = fixture()
        every {
            fixture.service.review(fixture.actor, "request-v1", "APPROVED", "ok", false)
        } returns flatV1View()

        fixture.mvc.perform(
            post("/api/admin/institution-project-requests/request-v1/review")
                .json("""{"decision":"APPROVED","reviewNote":"ok","force":false}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.errorCode").doesNotExist())
            .andExpect(jsonPath("$.data.payloadVersion").doesNotExist())

        verify(exactly = 1) {
            fixture.service.review(fixture.actor, "request-v1", "APPROVED", "ok", false)
        }
    }

    @Test
    fun `v1 legal representative force denial remains HTTP 403`() {
        val fixture = fixture()
        every {
            fixture.service.review(fixture.actor, "request-v1", "APPROVED", "override", true)
        } throws AccessDeniedException("只有平台管理员可以强制处理")

        fixture.mvc.perform(
            post("/api/admin/institution-project-requests/request-v1/review")
                .json("""{"decision":"APPROVED","reviewNote":"override","force":true}""")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.errorCode").doesNotExist())
    }

    @Test
    fun `v1 review rejects a v2 request with upgrade required`() {
        val fixture = fixture()
        every {
            fixture.service.review(fixture.actor, "request-v2", "APPROVED", "ok", false)
        } throws upgradeRequired()

        fixture.mvc.perform(
            post("/api/admin/institution-project-requests/request-v2/review")
                .json("""{"decision":"APPROVED","reviewNote":"ok","force":false}""")
        )
            .andExpect(status().`is`(426))
            .andExpect(jsonPath("$.code").value(426))
            .andExpect(jsonPath("$.errorCode").value("CLIENT_UPGRADE_REQUIRED"))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `v1 withdraw rejects a v2 request with upgrade required`() {
        val fixture = fixture()
        every { fixture.service.withdraw(fixture.actor, "request-v2") } throws upgradeRequired()

        fixture.mvc.perform(post("/api/admin/institution-project-requests/request-v2/withdraw").authenticated())
            .andExpect(status().`is`(426))
            .andExpect(jsonPath("$.code").value(426))
            .andExpect(jsonPath("$.errorCode").value("CLIENT_UPGRADE_REQUIRED"))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    private fun fixture(v1ProfileUpdateEnabled: Boolean? = null): Fixture {
        val service = mockk<DoctorProjectChangeService>()
        val access = mockk<ManagementAccessService>()
        val actor = ManagementActor(
            userId = "doctor-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR"),
            doctorId = "doctor-1",
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = setOf("inst-1"),
            manageableDoctorIds = emptySet(),
            consultantInstitutionIds = emptySet()
        )
        every { access.actor(any()) } returns actor
        val compatibility = ProjectChangeCompatibilityProperties().also { properties ->
            v1ProfileUpdateEnabled?.let { properties.v1ProfileUpdateEnabled = it }
        }
        val controller = DoctorProjectChangeController(service, access, mapper, compatibility)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .build()
        return Fixture(mvc, service, actor)
    }

    private fun MockHttpServletRequestBuilder.authenticated(): MockHttpServletRequestBuilder =
        principal(UsernamePasswordAuthenticationToken("doctor-1", "", emptyList()))

    private fun MockHttpServletRequestBuilder.json(body: String): MockHttpServletRequestBuilder =
        authenticated().contentType(MediaType.APPLICATION_JSON).content(body)

    private data class Fixture(
        val mvc: MockMvc,
        val service: DoctorProjectChangeService,
        val actor: ManagementActor
    )

    private companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val golden = mapper.readTree(File("../test-fixtures/doctor-project-change-v2.json"))

        fun flatV1Node(): ObjectNode = golden.path("requestV1").deepCopy<ObjectNode>().also {
            it.remove("payloadVersion")
        }

        fun flatV1View(): DoctorProjectChangeView =
            mapper.treeToValue(flatV1Node(), DoctorProjectChangeView::class.java)

        fun legacyProfileBody(): String = """{
            "institutionProjectId":"ip-1",
            "requestType":"PROFILE_UPDATE",
            "serviceDescription":"Legacy description",
            "priceSuggestion":1000.00,
            "notes":"Legacy request",
            "serviceTags":["legacy"],
            "scheduleNote":"Weekdays",
            "coverImage":"legacy-cover.jpg",
            "images":["legacy-1.jpg"],
            "consultationFee":50.00,
            "commissionRate":10.00,
            "institutionRate":20.00,
            "medicalListPrice":1000.00
        }"""

        fun upgradeRequired() = ProjectChangeContractException(
            HttpStatus.UPGRADE_REQUIRED,
            ProjectChangeErrorCode.CLIENT_UPGRADE_REQUIRED,
            "请升级客户端"
        )
    }
}
