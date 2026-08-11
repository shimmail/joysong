package com.joysong.server.institution.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.ManagedInstitutionProfile
import com.joysong.server.institution.service.ManagedInstitutionProfileNotFoundException
import com.joysong.server.institution.service.ManagedInstitutionProfileService
import com.joysong.server.institution.service.ManagedInstitutionProfileUpdateCommand
import com.joysong.server.institution.service.ManagedInstitutionSummary
import com.joysong.server.user.repository.UserRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.stream.Stream

@WebMvcTest
@ContextConfiguration(
    classes = [
        ManagedInstitutionProfileControllerTestConfig::class,
        SecurityConfig::class,
        GlobalExceptionHandler::class
    ]
)
class ManagedInstitutionProfileControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var accessService: ManagementAccessService

    @Autowired
    private lateinit var profileService: ManagedInstitutionProfileService

    private val legalActor = actor(
        userId = "legal-1",
        roles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        managedInstitutionIds = setOf("institution-1")
    )

    @BeforeEach
    fun resetMocks() {
        clearMocks(accessService, profileService)
    }

    @ParameterizedTest(name = "unauthenticated {0} returns 401")
    @MethodSource("managementRequests")
    fun `all management routes require authentication`(
        @Suppress("UNUSED_PARAMETER") label: String,
        request: MockHttpServletRequestBuilder
    ) {
        mockMvc.perform(request)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value(401))
            .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"))
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `ACTIVE legal representative lists only managed institution summaries`() {
        every { accessService.actor(any()) } returns legalActor
        every { profileService.list(legalActor) } returns listOf(summary())

        mockMvc.perform(get(BASE_PATH))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("institution-1"))
            .andExpect(jsonPath("$.data[0].name").value("悦颜医疗"))
            .andExpect(jsonPath("$.data[0].rating").value(4.7))
            .andExpect(jsonPath("$.data[0].isVerified").value(true))
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `ACTIVE legal representative gets a complete managed institution profile`() {
        every { accessService.actor(any()) } returns legalActor
        every { profileService.get(legalActor, "institution-1") } returns profile()

        mockMvc.perform(get("$BASE_PATH/institution-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.id").value("institution-1"))
            .andExpect(jsonPath("$.data.name").value("悦颜医疗"))
            .andExpect(jsonPath("$.data.images[0]").value("room.png"))
            .andExpect(jsonPath("$.data.establishedYear").value(2012))
            .andExpect(jsonPath("$.data.rating").value(4.7))
            .andExpect(jsonPath("$.data.certificationTime").value("2025-03-04"))
            .andExpect(jsonPath("$.data.userCount").value(41))
            .andExpect(jsonPath("$.data.createdAt").value("2024-01-02T03:04:05"))
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `ACTIVE legal representative updates all and only thirteen editable properties including explicit null year`() {
        val command = slot<ManagedInstitutionProfileUpdateCommand>()
        every { accessService.actor(any()) } returns legalActor
        every { profileService.update(legalActor, "institution-1", capture(command)) } returns
            profile(name = "新悦颜医疗", establishedYear = null)

        mockMvc.perform(
            put("$BASE_PATH/institution-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload(year = "null"))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.name").value("新悦颜医疗"))
            .andExpect(jsonPath("$.data.establishedYear").doesNotExist())
            .andExpect(jsonPath("$.data.rating").value(4.7))
            .andExpect(jsonPath("$.data.isVerified").value(true))

        assertEquals(
            ManagedInstitutionProfileUpdateCommand(
                name = "  新悦颜医疗  ",
                address = "南京西路 1 号",
                city = "上海",
                description = "专注医美",
                coverImage = "cover-new.png",
                images = listOf("room-new.png", "hall-new.png"),
                establishedYear = null,
                credentials = "医疗机构执业许可证",
                credentialImages = listOf("license-new.png"),
                specialties = listOf("皮肤", "抗衰"),
                tags = listOf("精品", "连锁"),
                contactPhone = "021-12345678",
                businessHours = "09:00-18:00"
            ),
            command.captured
        )
    }

    @ParameterizedTest(name = "strict PUT rejects {0}")
    @MethodSource("invalidPayloads")
    @WithMockUser(username = "legal-1")
    fun `PUT rejects malformed shape and field types with matching HTTP and body 400`(
        @Suppress("UNUSED_PARAMETER") label: String,
        payload: String
    ) {
        every { accessService.actor(any()) } returns legalActor

        mockMvc.perform(
            put("$BASE_PATH/institution-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
    }

    @ParameterizedTest(name = "{0} cannot use institution self-management")
    @ValueSource(strings = ["doctor", "consultant", "ordinary", "pending", "revoked", "admin"])
    @WithMockUser(username = "user")
    fun `non-active legal representative actors receive HTTP and body 403`(kind: String) {
        val actor = when (kind) {
            "doctor" -> actor("doctor-1", setOf("DOCTOR"), doctorId = "doctor-1")
            "consultant" -> actor("consultant-1", setOf("CONSULTANT"))
            "admin" -> actor("admin-1", setOf("ADMIN"), isAdmin = true)
            else -> actor("user-1", emptySet())
        }
        every { accessService.actor(any()) } returns actor

        mockMvc.perform(get(BASE_PATH))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @ParameterizedTest(name = "unmanaged institution {0} receives 403")
    @MethodSource("unmanagedRequests")
    @WithMockUser(username = "legal-1")
    fun `unmanaged detail routes return matching HTTP and body 403`(
        @Suppress("UNUSED_PARAMETER") label: String,
        request: MockHttpServletRequestBuilder
    ) {
        every { accessService.actor(any()) } returns legalActor
        every { profileService.get(legalActor, "institution-2") } throws
            AccessDeniedException("只有该机构已确认的法人可以修改机构信息")
        every { profileService.update(legalActor, "institution-2", any()) } throws
            AccessDeniedException("只有该机构已确认的法人可以修改机构信息")

        mockMvc.perform(request)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.message").value("只有该机构已确认的法人可以修改机构信息"))
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `missing institution maps to matching HTTP and body 404`() {
        every { accessService.actor(any()) } returns legalActor
        every { profileService.get(legalActor, "missing") } throws ManagedInstitutionProfileNotFoundException()

        mockMvc.perform(get("$BASE_PATH/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("机构档案不存在"))
    }

    @Test
    @WithMockUser(username = "legal-1")
    fun `unexpected route failure returns safe matching HTTP and body 500`() {
        val secret = "jdbc-secret-marker"
        every { accessService.actor(any()) } returns legalActor
        every { profileService.get(legalActor, "institution-1") } throws RuntimeException(secret)

        mockMvc.perform(get("$BASE_PATH/institution-1"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("服务器内部错误"))
            .andExpect { result -> assertFalse(result.response.contentAsString.contains(secret)) }
    }

    @Test
    @WithMockUser(username = "doctor-1", roles = ["DOCTOR"])
    fun `professional can no longer write through legacy admin institution route`() {
        mockMvc.perform(
            put("/api/admin/institutions/institution-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    private fun summary() = ManagedInstitutionSummary(
        id = "institution-1",
        name = "悦颜医疗",
        address = "南京西路 1 号",
        city = "上海",
        coverImage = "cover.png",
        rating = BigDecimal("4.7"),
        reviewCount = 23,
        isVerified = true
    )

    private fun profile(
        name: String = "悦颜医疗",
        establishedYear: Int? = 2012
    ) = ManagedInstitutionProfile(
        id = "institution-1",
        name = name,
        address = "南京西路 1 号",
        city = "上海",
        description = "专注医美",
        coverImage = "cover.png",
        images = listOf("room.png", "hall.png"),
        establishedYear = establishedYear,
        credentials = "医疗机构执业许可证",
        credentialImages = listOf("license.png"),
        specialties = listOf("皮肤", "抗衰"),
        tags = listOf("精品", "连锁"),
        contactPhone = "021-12345678",
        businessHours = "09:00-18:00",
        rating = BigDecimal("4.7"),
        reviewCount = 23,
        isVerified = true,
        certificationTime = LocalDate.of(2025, 3, 4),
        projectCount = 7,
        doctorCount = 9,
        consultationCount = 31,
        userCount = 41,
        caseCount = 19,
        createdAt = LocalDateTime.of(2024, 1, 2, 3, 4, 5),
        updatedAt = LocalDateTime.of(2026, 8, 11, 6, 7, 8)
    )

    companion object {
        private const val BASE_PATH = "/api/management/institutions"

        @JvmStatic
        fun managementRequests(): Stream<Arguments> = Stream.of(
            Arguments.of("list", get(BASE_PATH)),
            Arguments.of("detail", get("$BASE_PATH/institution-1")),
            Arguments.of(
                "update",
                put("$BASE_PATH/institution-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validPayload())
            )
        )

        @JvmStatic
        fun unmanagedRequests(): Stream<Arguments> = Stream.of(
            Arguments.of("GET", get("$BASE_PATH/institution-2")),
            Arguments.of(
                "PUT",
                put("$BASE_PATH/institution-2")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validPayload())
            )
        )

        @JvmStatic
        fun invalidPayloads(): Stream<Arguments> {
            val valid = validPayload()
            return Stream.of(
                Arguments.of("root array", "[]"),
                Arguments.of("root null", "null"),
                Arguments.of("missing name", valid.replace("  \"name\": \"  新悦颜医疗  \",\n", "")),
                Arguments.of("missing establishedYear", valid.replace("  \"establishedYear\": 2016,\n", "")),
                Arguments.of("unknown key", valid.replace("\n}", ",\n  \"unknown\": true\n}")),
                Arguments.of("platform rating", valid.replace("\n}", ",\n  \"rating\": 1\n}")),
                Arguments.of("null string", valid.replace("\"address\": \"南京西路 1 号\"", "\"address\": null")),
                Arguments.of("blank name", valid.replace("\"  新悦颜医疗  \"", "\"   \"")),
                Arguments.of("null array", valid.replace("\"images\": [\"room-new.png\", \"hall-new.png\"]", "\"images\": null")),
                Arguments.of("non-array list", valid.replace("\"tags\": [\"精品\", \"连锁\"]", "\"tags\": \"精品\"")),
                Arguments.of("non-string array item", valid.replace("\"specialties\": [\"皮肤\", \"抗衰\"]", "\"specialties\": [\"皮肤\", 3]")),
                Arguments.of("string year", valid.replace("\"establishedYear\": 2016", "\"establishedYear\": \"2016\"")),
                Arguments.of("fractional year", valid.replace("\"establishedYear\": 2016", "\"establishedYear\": 2016.5")),
                Arguments.of("out of range year", valid.replace("\"establishedYear\": 2016", "\"establishedYear\": 1799"))
            )
        }

        private fun validPayload(year: String = "2016") =
            """
            {
              "name": "  新悦颜医疗  ",
              "address": "南京西路 1 号",
              "city": "上海",
              "description": "专注医美",
              "coverImage": "cover-new.png",
              "images": ["room-new.png", "hall-new.png"],
              "establishedYear": $year,
              "credentials": "医疗机构执业许可证",
              "credentialImages": ["license-new.png"],
              "specialties": ["皮肤", "抗衰"],
              "tags": ["精品", "连锁"],
              "contactPhone": "021-12345678",
              "businessHours": "09:00-18:00"
            }
            """.trimIndent()

        private fun actor(
            userId: String,
            roles: Set<String>,
            managedInstitutionIds: Set<String> = emptySet(),
            doctorId: String? = null,
            isAdmin: Boolean = false
        ) = ManagementActor(
            userId = userId,
            isAdmin = isAdmin,
            activeRoles = roles,
            doctorId = doctorId,
            managedInstitutionIds = managedInstitutionIds,
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = doctorId?.let(::setOf) ?: emptySet()
        )
    }
}

@TestConfiguration
@ComponentScan(basePackages = ["com.joysong.server.institution.controller"])
@Import(JwtAuthenticationFilter::class)
class ManagedInstitutionProfileControllerTestConfig {
    @Bean
    fun managementAccessService(): ManagementAccessService = mockk()

    @Bean
    fun managedInstitutionProfileService(): ManagedInstitutionProfileService = mockk()

    @Bean
    fun jwtTokenProvider(): JwtTokenProvider = mockk(relaxed = true)

    @Bean
    fun userRepository(): UserRepository = mockk(relaxed = true)
}
