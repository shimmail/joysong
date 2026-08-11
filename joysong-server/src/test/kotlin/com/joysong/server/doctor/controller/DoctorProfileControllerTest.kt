package com.joysong.server.doctor.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.doctor.service.DoctorInstitutionView
import com.joysong.server.doctor.service.DoctorProfileNotFoundException
import com.joysong.server.doctor.service.DoctorProfileService
import com.joysong.server.doctor.service.DoctorProfileUpdateCommand
import com.joysong.server.doctor.service.DoctorProfileView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal

@WebMvcTest
@ContextConfiguration(
    classes = [
        DoctorProfileControllerTestConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class DoctorProfileControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var accessService: ManagementAccessService

    @Autowired
    private lateinit var profileService: DoctorProfileService

    private val actor = ManagementActor(
        userId = "doctor-1",
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = "doctor-1",
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf("institution-1", "institution-2"),
        manageableDoctorIds = setOf("doctor-1")
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(accessService, profileService)
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `GET returns one complete doctor profile derived from authenticated actor`() {
        val authentication = slot<Authentication>()
        every { accessService.actor(capture(authentication)) } returns actor
        every { profileService.get(actor) } returns completeView()

        mockMvc.perform(get("/api/management/doctor-profile"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value("doctor-1"))
            .andExpect(jsonPath("$.data[0]").doesNotExist())
            .andExpect(jsonPath("$.data.userId").value("doctor-user-1"))
            .andExpect(jsonPath("$.data.name").value("李医生"))
            .andExpect(jsonPath("$.data.title").value("主任医师"))
            .andExpect(jsonPath("$.data.bio").value("擅长修复"))
            .andExpect(jsonPath("$.data.avatar").value("avatar.png"))
            .andExpect(jsonPath("$.data.contactPhone").value("13800000000"))
            .andExpect(jsonPath("$.data.specialties").value("种植,正畸"))
            .andExpect(jsonPath("$.data.credentials").value("医师资格证"))
            .andExpect(jsonPath("$.data.credentialImages").value("a.png,b.png"))
            .andExpect(jsonPath("$.data.certificationTags").value("三甲,专家"))
            .andExpect(jsonPath("$.data.institutionId").value("institution-legacy"))
            .andExpect(jsonPath("$.data.institutionName").value("旧机构"))
            .andExpect(jsonPath("$.data.institutions[0].id").value("institution-1"))
            .andExpect(jsonPath("$.data.institutions[0].name").value("第一医院"))
            .andExpect(jsonPath("$.data.institutions[1].id").value("institution-2"))
            .andExpect(jsonPath("$.data.institutions[1].name").value("第二医院"))
            .andExpect(jsonPath("$.data.primaryInstitution.id").value("institution-1"))
            .andExpect(jsonPath("$.data.primaryInstitution.name").value("第一医院"))
            .andExpect(jsonPath("$.data.institutionCount").value(2))
            .andExpect(jsonPath("$.data.rating").value(4.8))
            .andExpect(jsonPath("$.data.reviewCount").value(13))
            .andExpect(jsonPath("$.data.isVerified").value(true))
            .andExpect(jsonPath("$.data.consultationCount").value(21))
            .andExpect(jsonPath("$.data.caseCount").value(8))

        assertEquals("doctor-1", authentication.captured.name)
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `PUT converts only nine editable properties and returns the complete updated profile`() {
        val authentication = slot<Authentication>()
        val command = slot<DoctorProfileUpdateCommand>()
        every { accessService.actor(capture(authentication)) } returns actor
        every { profileService.update(actor, capture(command)) } returns completeView(name = "新姓名")

        mockMvc.perform(
            put("/api/management/doctor-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "  新姓名  ",
                      "title": "新职称",
                      "bio": "新简介",
                      "avatar": "new.png",
                      "contactPhone": "13900000000",
                      "specialties": "皮肤,修复",
                      "credentials": "新资质",
                      "credentialImages": "new-a.png,new-b.png",
                      "certificationTags": "专家,认证",
                      "rating": 1.0,
                      "isVerified": false,
                      "institutionId": "attacker-institution",
                      "institutionName": "攻击者机构",
                      "institutions": []
                    }
                    """.trimIndent()
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value("doctor-1"))
            .andExpect(jsonPath("$.data.name").value("新姓名"))
            .andExpect(jsonPath("$.data.institutionId").value("institution-legacy"))
            .andExpect(jsonPath("$.data.rating").value(4.8))
            .andExpect(jsonPath("$.data.isVerified").value(true))

        assertEquals("doctor-1", authentication.captured.name)
        assertEquals(
            DoctorProfileUpdateCommand(
                name = "  新姓名  ",
                title = "新职称",
                bio = "新简介",
                avatar = "new.png",
                contactPhone = "13900000000",
                specialties = "皮肤,修复",
                credentials = "新资质",
                credentialImages = "new-a.png,new-b.png",
                certificationTags = "专家,认证"
            ),
            command.captured
        )
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `PUT rejects an omitted property with HTTP and body 400`() {
        assertPutBadRequest(
            """
            {
              "name": "李医生",
              "title": "",
              "bio": "",
              "avatar": "",
              "contactPhone": "",
              "specialties": "",
              "credentials": "",
              "credentialImages": ""
            }
            """.trimIndent()
        )
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `PUT rejects an explicit null property with HTTP and body 400`() {
        assertPutBadRequest(validPayload().replace("\"title\": \"\"", "\"title\": null"))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `PUT rejects malformed JSON with HTTP and body 400`() {
        assertPutBadRequest("""{"name": "李医生"""")
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `PUT rejects a blank name with HTTP and body 400`() {
        every { accessService.actor(any()) } returns actor

        mockMvc.perform(
            put("/api/management/doctor-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload().replace("\"name\": \"李医生\"", "\"name\": \"   \""))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("name 不能为空"))
    }

    @Test
    @WithMockUser(username = "doctor-1")
    fun `GET maps a missing active doctor profile to HTTP and body 404`() {
        every { accessService.actor(any()) } returns actor
        every { profileService.get(actor) } throws DoctorProfileNotFoundException()

        mockMvc.perform(get("/api/management/doctor-profile"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("医生档案不存在"))
    }

    @Test
    @WithMockUser(username = "consultant-1")
    fun `GET maps a non-doctor actor denial to HTTP and body 403`() {
        every { accessService.actor(any()) } returns actor.copy(
            userId = "consultant-1",
            activeRoles = setOf("CONSULTANT"),
            doctorId = null,
            manageableDoctorIds = emptySet()
        )
        every { profileService.get(any()) } throws AccessDeniedException("当前账号没有有效医生身份")

        mockMvc.perform(get("/api/management/doctor-profile"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("当前账号没有有效医生身份"))
    }

    @Test
    fun `unauthenticated GET returns HTTP and body 401`() {
        mockMvc.perform(get("/api/management/doctor-profile"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"))
    }

    @Test
    fun `unauthenticated PUT returns HTTP and body 401`() {
        mockMvc.perform(
            put("/api/management/doctor-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload())
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"))
    }

    private fun assertPutBadRequest(payload: String) {
        mockMvc.perform(
            put("/api/management/doctor-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    private fun validPayload() =
        """
        {
          "name": "李医生",
          "title": "",
          "bio": "",
          "avatar": "",
          "contactPhone": "",
          "specialties": "",
          "credentials": "",
          "credentialImages": "",
          "certificationTags": ""
        }
        """.trimIndent()

    private fun completeView(name: String = "李医生") = DoctorProfileView(
        id = "doctor-1",
        userId = "doctor-user-1",
        name = name,
        title = "主任医师",
        bio = "擅长修复",
        avatar = "avatar.png",
        contactPhone = "13800000000",
        specialties = "种植,正畸",
        credentials = "医师资格证",
        credentialImages = "a.png,b.png",
        certificationTags = "三甲,专家",
        institutionId = "institution-legacy",
        institutionName = "旧机构",
        institutions = listOf(
            DoctorInstitutionView("institution-1", "第一医院"),
            DoctorInstitutionView("institution-2", "第二医院")
        ),
        primaryInstitution = DoctorInstitutionView("institution-1", "第一医院"),
        institutionCount = 2,
        rating = BigDecimal("4.8"),
        reviewCount = 13,
        isVerified = true,
        consultationCount = 21,
        caseCount = 8
    )
}

@TestConfiguration
@ComponentScan(basePackages = ["com.joysong.server.doctor.controller"])
@Import(JwtAuthenticationFilter::class)
class DoctorProfileControllerTestConfig {
    @Bean
    fun managementAccessService(): ManagementAccessService = mockk()

    @Bean
    fun doctorProfileService(): DoctorProfileService = mockk()

    @Bean
    fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

    @Bean
    fun userRepository(): UserRepository = mockk(relaxed = true)
}
