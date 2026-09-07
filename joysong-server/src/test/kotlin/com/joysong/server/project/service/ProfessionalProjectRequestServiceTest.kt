package com.joysong.server.project.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.money.CurrencyCode
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityOperations
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.project.controller.AdminProfessionalProjectRequestController
import com.joysong.server.project.controller.ProfessionalProjectRequestController
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.cache.CacheManager
import org.springframework.security.access.AccessDeniedException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

class ProfessionalProjectRequestServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val splitRatePolicy = OrderSplitRatePolicy(OrderSplitProperties().apply {
        platformRate = BigDecimal("40.00")
    })
    private val reviewAuthority = mockk<InstitutionRelationshipReviewAuthorityOperations>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val businessNotifications = mockk<BusinessNotificationService>(relaxed = true)
    private val service = ProfessionalProjectRequestService(
        jdbcTemplate,
        objectMapper,
        splitRatePolicy,
        reviewAuthority,
        cacheManager,
        businessNotifications,
        InstitutionProjectPayloadPolicy()
    )

    init {
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") },
                Long::class.java,
                *anyVararg()
            )
        } returns 1L
    }

    @Test
    fun `platform submission persists a complete trimmed immutable snapshot`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1

        val result = service.submitPlatform(
            doctorActor(),
            DoctorPlatformProjectRequest(
                name = "  Laser  ",
                category = " Skin ",
                description = " Description ",
                referencePrice = BigDecimal("199.90"),
                currency = CurrencyCode.USD,
                slogan = "  Clear skin  ",
                salesCount = 12,
                coverImage = " cover.png ",
                images = listOf(" one.png ", "two.png"),
                detailContent = "  Details  ",
                tags = listOf(" bright ", "laser"),
                categoryTags = listOf(" face "),
                notes = " Notes "
            )
        )

        assertEquals("PLATFORM", result.requestType)
        assertEquals("PENDING", result.status)
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationSubmitted(any(), any())
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("INSERT INTO professional_project_requests") &&
                        it.contains("reference_price") && it.contains("category_tags") &&
                        it.substringAfter("VALUES").count { character -> character == '?' } == 24 &&
                        !it.contains("platform_rate") && !it.contains("doctor_rate")
                },
                any(), "PLATFORM", "doctor-1", null, null,
                "Laser", "Skin", "Description", "[\"bright\",\"laser\"]", "Clear skin", "Details",
                "USD", "cover.png", "[\"one.png\",\"two.png\"]", 12,
                BigDecimal("199.90"), "[\"face\"]",
                null, null, null, null, null, null,
                "Notes"
            )
        }
    }

    @Test
    fun `institution submission persists overrides and only the actor doctor with submitted split values`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1

        val result = service.submitInstitution(
            doctorActor(),
            " institution-1 ",
            DoctorInstitutionProjectRequest(
                projectId = " project-1 ",
                name = "  Local laser  ",
                category = "  ",
                description = "  Service  ",
                tags = listOf(" local "),
                slogan = null,
                detailContent = "  Local details ",
                price = BigDecimal("99.00"),
                originalPrice = BigDecimal("120.00"),
                currency = CurrencyCode.USD,
                coverImage = " local.png ",
                images = listOf(" local-1.png "),
                salesCount = 3,
                isActive = false,
                consultationFee = BigDecimal("10.00"),
                commissionRate = BigDecimal("15.00"),
                institutionRate = BigDecimal("25.00"),
                notes = "  institution note  "
            )
        )

        assertEquals("INSTITUTION", result.requestType)
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("INSERT INTO professional_project_requests") &&
                        it.contains("consultation_fee") && it.contains("institution_rate") &&
                        !it.contains("platform_rate") && !it.contains("doctor_rate")
                },
                any(), "INSTITUTION", "doctor-1", "institution-1", "project-1",
                "Local laser", null, "Service", "[\"local\"]", "", "Local details",
                "USD", "local.png", "[\"local-1.png\"]", 3,
                null, null,
                BigDecimal("99.00"), BigDecimal("120.00"), false,
                BigDecimal("10.00"), BigDecimal("15.00"), BigDecimal("25.00"),
                "institution note"
            )
        }
        verify(exactly = 1) {
            businessNotifications.institutionProjectApplicationSubmitted("institution-1", result.id)
        }
    }

    @Test
    fun `institution submission does not notify when insert affects zero rows`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 0

        assertThrows<ProfessionalProjectRequestConflictException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                DoctorInstitutionProjectRequest(
                    projectId = "project-1",
                    description = "service",
                    price = BigDecimal("99.00"),
                    notes = "notes"
                )
            )
        }

        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationSubmitted(any(), any())
        }
    }

    @Test
    fun `blank institution overrides are stored as inheritance sentinels`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1

        service.submitInstitution(
            doctorActor(),
            "institution-1",
            DoctorInstitutionProjectRequest(
                projectId = "project-1",
                name = " ", category = null, description = " ", tags = emptyList(), slogan = " ",
                detailContent = " ", coverImage = null, images = emptyList(),
                price = BigDecimal("0.00"), consultationFee = BigDecimal("0.00"),
                commissionRate = BigDecimal("0.00"), institutionRate = BigDecimal("0.00")
            )
        )

        verify {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO professional_project_requests") },
                any(), "INSTITUTION", "doctor-1", "institution-1", "project-1",
                null, null, null, null, "", null, "USD", "", null, 0,
                null, null, BigDecimal("0.00"), null, true,
                BigDecimal("0.00"), BigDecimal("0.00"), BigDecimal("0.00"), null
            )
        }
    }

    @Test
    fun `shared payload policy normalizes inheritance markers but keeps explicit nonnegative sales count`() {
        val normalized = InstitutionProjectPayloadPolicy().normalize(
            InstitutionProjectPayload(
                name = "  Local  ", category = " ", description = null, tags = emptyList(), slogan = " ",
                detailContent = " ", coverImage = " ", images = emptyList(), salesCount = 3
            )
        )

        assertEquals("Local", normalized.name)
        assertEquals(null, normalized.category)
        assertEquals(null, normalized.tags)
        assertEquals(null, normalized.coverImage)
        assertEquals(null, normalized.images)
        assertEquals(3, normalized.salesCount)
        val error = assertThrows<ProjectChangeContractException> {
            InstitutionProjectPayloadPolicy().normalize(InstitutionProjectPayload(salesCount = -1))
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, error.errorCode)
        val response = GlobalExceptionHandler().handleProjectChangeContract(error)
        assertEquals(
            "PROJECT_PAYLOAD_INVALID",
            objectMapper.readTree(objectMapper.writeValueAsString(response.body)).path("errorCode").asText()
        )
    }

    @Test
    fun `shared payload policy rejects blank members in nonempty tag and image lists`() {
        val invalidPayloads = listOf(
            InstitutionProjectPayload(tags = listOf("valid", " ")),
            InstitutionProjectPayload(images = listOf(" ", "valid.png"))
        )

        invalidPayloads.forEach { payload ->
            val error = assertThrows<ProjectChangeContractException> {
                InstitutionProjectPayloadPolicy().normalize(payload)
            }
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
            assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, error.errorCode)
        }
    }

    @Test
    fun `institution submission rejects blank list members as a coded payload error before writes`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1
        val invalidRequests = listOf(
            DoctorInstitutionProjectRequest(projectId = "project-1", tags = listOf("valid", " ")),
            DoctorInstitutionProjectRequest(projectId = "project-1", images = listOf(" ", "valid.png"))
        )

        invalidRequests.forEach { request ->
            val error = assertThrows<ProjectChangeContractException> {
                service.submitInstitution(doctorActor(), "institution-1", request)
            }
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status)
            assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, error.errorCode)
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submission rejects invalid money counts arrays and bounded text before insert`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1
        val invalidPlatformRequests = listOf(
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", currency = CurrencyCode.CNY),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", referencePrice = BigDecimal("-0.01")),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", referencePrice = BigDecimal("100000000.00")),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", referencePrice = BigDecimal("1.001")),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", salesCount = -1),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", tags = List(21) { "tag-$it" }),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", images = listOf("  ")),
            DoctorPlatformProjectRequest(name = "P".repeat(201), category = "C", description = "D"),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D".repeat(5_001)),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", slogan = "S".repeat(501)),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", coverImage = "C".repeat(501)),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", detailContent = "D".repeat(20_001)),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", notes = "N".repeat(2_001)),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", tags = listOf("T".repeat(101))),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", images = listOf("I".repeat(501)))
        )

        invalidPlatformRequests.forEach { request ->
            assertThrows<IllegalArgumentException> { service.submitPlatform(doctorActor(), request) }
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submission accepts exact comma separated target column boundaries`() {
        val tagsAtLimit = List(5) { index -> "t".repeat(if (index == 4) 96 else 100) }
        val categoryTagsAtLimit = List(5) { index -> "c".repeat(if (index == 4) 96 else 100) }
        val imagesAtLimit = List(4) { index -> "i".repeat(if (index == 3) 497 else 500) }
        assertEquals(500, tagsAtLimit.joinToString(",").length)
        assertEquals(500, categoryTagsAtLimit.joinToString(",").length)
        assertEquals(2_000, imagesAtLimit.joinToString(",").length)
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1

        service.submitPlatform(
            doctorActor(),
            DoctorPlatformProjectRequest(
                name = "P", category = "C", description = "D",
                tags = tagsAtLimit, categoryTags = categoryTagsAtLimit, images = imagesAtLimit
            )
        )
        service.submitInstitution(
            doctorActor(),
            "institution-1",
            DoctorInstitutionProjectRequest(
                projectId = "project-1", tags = tagsAtLimit, images = imagesAtLimit
            )
        )

        verify(exactly = 2) {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg())
        }
    }

    @Test
    fun `submission rejects comma separated target values over target columns before insert`() {
        val tagsOverLimit = List(5) { "t".repeat(100) }
        val imagesOverLimit = List(4) { "i".repeat(500) }
        assertEquals(504, tagsOverLimit.joinToString(",").length)
        assertEquals(2_003, imagesOverLimit.joinToString(",").length)
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") },
                Long::class.java,
                "institution-1"
            )
        } returns 1L
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val platformRequests = listOf(
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", tags = tagsOverLimit),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", categoryTags = tagsOverLimit),
            DoctorPlatformProjectRequest(name = "P", category = "C", description = "D", images = imagesOverLimit)
        )
        val institutionRequests = listOf(
            DoctorInstitutionProjectRequest(projectId = "project-1", tags = tagsOverLimit),
            DoctorInstitutionProjectRequest(projectId = "project-1", images = imagesOverLimit)
        )

        platformRequests.forEach { request ->
            assertThrows<IllegalArgumentException> { service.submitPlatform(doctorActor(), request) }
        }
        institutionRequests.forEach { request ->
            val error = assertThrows<ProjectChangeContractException> {
                service.submitInstitution(doctorActor(), "institution-1", request)
            }
            assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, error.errorCode)
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution submission validates every amount and delegated split rates`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1
        val invalidLegacyRequests = listOf(
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", price = BigDecimal("1.001")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", originalPrice = BigDecimal("-0.01")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", consultationFee = BigDecimal("100000000.00")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", commissionRate = BigDecimal("60.00"), institutionRate = BigDecimal("50.00"))
        )

        invalidLegacyRequests.forEach { request ->
            assertThrows<IllegalArgumentException> {
                service.submitInstitution(doctorActor(), "institution-1", request)
            }
        }
        val payloadError = assertThrows<ProjectChangeContractException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", salesCount = -1)
            )
        }
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, payloadError.status)
        assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, payloadError.errorCode)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `list reconstructs complete platform snapshots and JSON arrays`() {
        stubListRows(listResultSet("PLATFORM"))

        val json = objectMapper.valueToTree<JsonNode>(service.list(adminActor()).single())

        assertEquals("Platform snapshot", json["name"].asText())
        assertEquals("SKIN", json["category"].asText())
        assertEquals("Snapshot description", json["description"].asText())
        assertEquals("[\"laser\",\"skin\"]", json["tags"].toString())
        assertEquals("Clear today", json["slogan"].asText())
        assertEquals("Snapshot details", json["detailContent"].asText())
        assertEquals("CNY", json["currency"].asText())
        assertEquals("cover.png", json["coverImage"].asText())
        assertEquals("[\"one.png\",\"two.png\"]", json["images"].toString())
        assertEquals(8, json["salesCount"].asInt())
        assertDecimal("199.90", json["referencePrice"])
        assertEquals("[\"face\"]", json["categoryTags"].toString())
        assertEquals(true, json["institutionSplit"].isNull)
        assertEquals("PENDING", json["status"].asText())
        assertEquals("reviewer-1", json["reviewedBy"].asText())
        assertEquals("result-project", json["resultingProjectId"].asText())
    }

    @Test
    fun `list endpoints expose imported blank review notes as explicit null without changing stored snapshots`() {
        val storedNotes = listOf("", " \t\r\n ", null, "Keep the original review")
        val rows = listOf("PLATFORM", "INSTITUTION").flatMap { type ->
            storedNotes.map { note ->
                listResultSet(type).also { rs ->
                    every { rs.getString("review_note") } returns note
                }
            }
        }
        stubListRows(*rows.toTypedArray())
        val access = mockk<ManagementAccessService>()
        every { access.actor(any()) } returns adminActor()
        val responseMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build<com.fasterxml.jackson.databind.ObjectMapper>()
        val mvc = MockMvcBuilders.standaloneSetup(
            AdminProfessionalProjectRequestController(service, access),
            ProfessionalProjectRequestController(service, access, splitRatePolicy, responseMapper)
        ).setMessageConverters(MappingJackson2HttpMessageConverter(responseMapper)).build()

        listOf("/api/admin/project-requests", "/api/management/project-requests").forEach { path ->
            val response = mvc.perform(get(path).principal(UsernamePasswordAuthenticationToken("admin-1", "")))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
            val snapshots = responseMapper.readTree(response)["data"]

            assertEquals(rows.size, snapshots.size())
            snapshots.forEachIndexed { index, snapshot ->
                val storedNote = storedNotes[index % storedNotes.size]
                assertEquals(true, snapshot.has("reviewNote"), path)
                if (storedNote.isNullOrBlank()) {
                    assertEquals(true, snapshot["reviewNote"].isNull, "$path snapshot $index")
                } else {
                    assertEquals(storedNote, snapshot["reviewNote"].asText())
                }
                assertEquals(true, snapshot.has("isActive"), path)
                assertEquals(index < storedNotes.size, snapshot["isActive"].isNull)
                assertEquals("2026-08-16T10:00:00", snapshot["submittedAt"].asText())
                assertEquals(storedNote, rows[index].getString("review_note"))
            }
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `list reconstructs legacy platform defaults safely`() {
        stubListRows(listResultSet("PLATFORM", legacy = true))

        val json = objectMapper.valueToTree<JsonNode>(service.list(adminActor()).single())

        assertEquals("[]", json["tags"].toString())
        assertEquals("[]", json["images"].toString())
        assertEquals("[]", json["categoryTags"].toString())
        assertEquals("USD", json["currency"].asText())
        assertEquals("0", json["referencePrice"].decimalValue().toPlainString())
        assertEquals("", json["slogan"].asText())
        assertEquals("", json["coverImage"].asText())
        assertEquals(0, json["salesCount"].asInt())
    }

    @Test
    fun `institution list exposes submitted and current split even when current rate drifts over one hundred`() {
        stubListRows(listResultSet("INSTITUTION", driftedSplit = true))

        val json = objectMapper.valueToTree<JsonNode>(service.list(adminActor()).single())
        val split = json["institutionSplit"]

        assertEquals("Institution override", json["name"].asText())
        assertEquals("SKIN", json["category"].asText())
        assertEquals("Snapshot description", json["description"].asText())
        assertEquals("[\"laser\",\"skin\"]", json["tags"].toString())
        assertEquals("Clear today", json["slogan"].asText())
        assertEquals("Snapshot details", json["detailContent"].asText())
        assertEquals("CNY", json["currency"].asText())
        assertEquals("cover.png", json["coverImage"].asText())
        assertEquals("[\"one.png\",\"two.png\"]", json["images"].toString())
        assertEquals(8, json["salesCount"].asInt())
        assertEquals(true, json["referencePrice"].isNull)
        assertEquals(true, json["categoryTags"].isNull)
        assertDecimal("99.00", json["price"])
        assertDecimal("120.00", json["originalPrice"])
        assertEquals(true, json["isActive"].asBoolean())
        assertDecimal("10.00", split["consultationFee"])
        assertDecimal("30.00", split["commissionRate"])
        assertDecimal("40.00", split["institutionRate"])
        assertDecimal("40.00", split["platformRate"])
        assertDecimal("-10.00", split["doctorRate"])
    }

    @Test
    fun `institution list restores non-null database sentinels to inheritance nulls`() {
        stubListRows(listResultSet("INSTITUTION", inheritanceSentinels = true))

        val json = objectMapper.valueToTree<JsonNode>(service.list(adminActor()).single())

        assertEquals(true, json["slogan"].isNull)
        assertEquals(true, json["coverImage"].isNull)
        assertEquals(true, json["tags"].isNull)
        assertEquals(true, json["images"].isNull)
    }

    @Test
    fun `list SQL applies applicant target representative and admin object scopes`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } returns emptyList()

        service.list(doctorActor())
        service.list(legalRepresentativeActor(setOf("institution-1")))
        service.list(adminActor())

        verify(exactly = 1) {
            jdbcTemplate.query(
                match<String> { it.contains("r.doctor_id = ?") && !it.contains("r.institution_id IN") },
                any<RowMapper<Any>>(),
                "doctor-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.query(
                match<String> {
                    it.contains("r.request_type = 'INSTITUTION'") && it.contains("r.institution_id IN (?)")
                },
                any<RowMapper<Any>>(),
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.query(
                match<String> { !it.contains("WHERE (") },
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        }
    }

    @Test
    fun `doctor cannot submit an institution request outside approved institutions`() {
        val error = assertThrows<AccessDeniedException> {
            service.submitInstitution(
                doctorActor(approvedInstitutions = setOf("institution-1")),
                "institution-2",
                DoctorInstitutionProjectRequest(projectId = "project-1", description = "service", price = BigDecimal("99.00"), notes = "notes")
            )
        }

        assertEquals("只能向已通过执业关系的机构提交项目申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `same pending institution request is rejected before insert`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 1L

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                DoctorInstitutionProjectRequest(projectId = "project-1", description = "service", price = BigDecimal("99.00"), notes = "notes")
            )
        }

        assertEquals("同一项目已有待处理申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution request is rejected when institution already has base project`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 1L

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                DoctorInstitutionProjectRequest(projectId = "project-1", description = "service", price = BigDecimal("99.00"), notes = "notes")
            )
        }

        assertEquals("该机构已存在此平台项目，请申请加入机构项目", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution submission rejects a missing or soft deleted institution despite actor scope`() {
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("deleted_at IS NULL") },
                Long::class.java,
                "institution-1"
            )
        } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val error = assertThrows<ProfessionalProjectRequestNotFoundException> {
            service.submitInstitution(
                doctorActor(approvedInstitutions = setOf("institution-1")),
                "institution-1",
                DoctorInstitutionProjectRequest(projectId = "project-1")
            )
        }

        assertEquals("机构不存在", error.message)
        verify(exactly = 0) {
            jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg())
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution request reports a missing platform project as not found`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 0L

        val error = assertThrows<ProfessionalProjectRequestNotFoundException> {
            service.submitInstitution(
                doctorActor(),
                "institution-1",
                DoctorInstitutionProjectRequest(projectId = "project-1", price = BigDecimal("99.00"))
            )
        }

        assertEquals("平台项目不存在", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `rejected decision requires review note before database access`() {
        val error = assertThrows<IllegalArgumentException> {
            service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("REJECTED", "  "))
        }
        assertEquals("拒绝时必须填写审核意见", error.message)
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `changes requested is rejected before database access even with a note`() {
        val error = assertThrows<IllegalArgumentException> {
            service.reviewPlatform(
                adminActor(),
                "request-1",
                ProjectRequestReview("CHANGES_REQUESTED", "Please revise")
            )
        }

        assertEquals("审核决定不正确", error.message)
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `platform approval writes the complete immutable snapshot with zero rating and reviews`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("PLATFORM", null), 0))
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("INSERT INTO projects") &&
                        it.contains("rating") && it.contains("review_count") &&
                        it.contains("reference_price") && it.contains("category_tags")
                },
                any(), "Project", "Category", "Service", "tag", "category-tag", "cover.png", "one.png",
                BigDecimal("199.00"), "USD", "Slogan", "Service details",
                BigDecimal.ZERO, 0, 1
            )
        }
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationApproved(any(), any())
        }
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationRejected(any(), any(), any())
        }
    }

    @Test
    fun `platform approval rejects a legacy non USD snapshot before creating a project`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("PLATFORM", null, currency = "CNY"), 0))
        }

        assertThrows<IllegalArgumentException> {
            service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        verify(exactly = 0) {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO projects") }, *anyVararg())
        }
    }

    @Test
    fun `institution approval writes the complete target snapshot binding and split config`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("INSERT INTO institution_projects") &&
                        it.contains("rating") && it.contains("review_count") && it.contains("is_active")
                },
                any(), "institution-1", "project-1", "Project", "Category", "Service",
                BigDecimal.ZERO, 0, "tag", "Slogan", "Service details", BigDecimal("99.00"),
                null, "USD", "cover.png", "one.png", 1, true
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("INSERT INTO doctor_projects") && it.contains("service_tags") &&
                        it.contains("cover_image") && it.contains("images")
                },
                "doctor-1", "project-1", any(), "Service", "tag", "", "cover.png", "one.png",
                BigDecimal("99.00")
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO doctor_institution_project_configs") },
                any(), "doctor-1", any(), BigDecimal("10.00"), BigDecimal("20.00"), BigDecimal("30.00")
            )
        }
    }

    @Test
    fun `institution approval treats legacy empty tag and image arrays as inheritance sentinels`() {
        stubLockedRequest(
            targetResultSet(
                "INSTITUTION",
                "institution-1",
                tagsJson = "[]",
                imagesJson = "[]"
            )
        )
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO institution_projects") },
                any(), "institution-1", "project-1", "Project", "Category", "Service",
                BigDecimal.ZERO, 0, "base-tag", "Slogan", "Service details", BigDecimal("99.00"),
                null, "USD", "cover.png", "base-one.png,base-two.png", 1, true
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO doctor_projects") },
                "doctor-1", "project-1", any(), "Service", "base-tag", "", "cover.png",
                "base-one.png,base-two.png", BigDecimal("99.00")
            )
        }
    }

    @Test
    fun `platform approval rejects legacy target lists over destination columns before writes`() {
        val oversizedTags = List(5) { "t".repeat(100) }
        stubLockedRequest(
            targetResultSet(
                "PLATFORM",
                null,
                tagsJson = objectMapper.writeValueAsString(oversizedTags)
            )
        )
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val error = assertThrows<IllegalArgumentException> {
            service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("项目标签不能超过 500 个字符", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution approval rejects legacy target lists over destination columns before writes`() {
        val oversizedImages = List(4) { "i".repeat(500) }
        stubLockedRequest(
            targetResultSet(
                "INSTITUTION",
                "institution-1",
                imagesJson = objectMapper.writeValueAsString(oversizedImages)
            )
        )
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val error = assertThrows<IllegalArgumentException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("项目图片不能超过 2000 个字符", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution reviewer cannot review another institution request`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-2"))
        every {
            reviewAuthority.requireCurrentAuthority(any(), "institution-2")
        } throws AccessDeniedException("无权审核其他机构的关系申请")

        val error = assertThrows<AccessDeniedException> {
            service.reviewInstitution(
                legalRepresentativeActor(setOf("institution-1")),
                "request-1",
                ProjectRequestReview("APPROVED")
            )
        }

        assertEquals("无权审核其他机构的关系申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `platform review checks administrator after locking the pending request`() {
        stubLockedRequest(targetResultSet("PLATFORM", null))

        val error = assertThrows<AccessDeniedException> {
            service.reviewPlatform(doctorActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("该操作仅限平台管理员", error.message)
        verify(exactly = 1) {
            jdbcTemplate.query(
                match<String> { it.contains("professional_project_requests") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                "request-1"
            )
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `current target institution authority is checked after locking pending request`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE professional_project_requests") }, *anyVararg()) } returns 1

        val actor = legalRepresentativeActor(emptySet())
        val result = service.reviewInstitution(actor, "request-1", ProjectRequestReview("REJECTED", "Not eligible"))

        assertEquals("REJECTED", result.status)
        verifyOrder {
            jdbcTemplate.query(
                match<String> { it.contains("professional_project_requests") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                "request-1"
            )
            reviewAuthority.requireCurrentAuthority(actor, "institution-1")
        }
        verify(exactly = 0) { cacheManager.getCache(any()) }
        verify(exactly = 1) {
            businessNotifications.institutionProjectApplicationRejected(
                "doctor-1",
                "request-1",
                "Not eligible"
            )
        }
    }

    @Test
    fun `approved review accepts an omitted note`() {
        stubLockedRequest(targetResultSet("PLATFORM", null))
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))

        assertEquals("APPROVED", result.status)
        verify {
            jdbcTemplate.update(
                match<String> { it.contains("UPDATE professional_project_requests") },
                "APPROVED", null, "admin-1", any(), null, "request-1"
            )
        }
    }

    @Test
    fun `platform rejection does not send institution project application notifications`() {
        stubLockedRequest(targetResultSet("PLATFORM", null))
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.reviewPlatform(
            adminActor(),
            "request-1",
            ProjectRequestReview("REJECTED", "Not eligible")
        )

        assertEquals("REJECTED", result.status)
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationApproved(any(), any())
        }
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationRejected(any(), any(), any())
        }
    }

    @Test
    fun `processed request produces conflict before target writes`() {
        stubLockedRequest(targetResultSet("PLATFORM", null, status = "APPROVED"))

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("项目申请已处理", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `concurrent compare and set loss produces conflict`() {
        stubLockedRequest(targetResultSet("PLATFORM", null))
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO projects") }, *anyVararg()) } returns 1
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE professional_project_requests") }, *anyVararg()) } returns 0

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("项目申请已被其他审核人处理", error.message)
    }

    @Test
    fun `institution compare and set loss creates no applicant notification`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        every {
            jdbcTemplate.update(
                match<String> { it.contains("UPDATE professional_project_requests") },
                *anyVararg()
            )
        } returns 0

        assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationApproved(any(), any())
        }
        verify(exactly = 0) {
            businessNotifications.institutionProjectApplicationRejected(any(), any(), any())
        }
    }

    @Test
    fun `vanished institution produces not found before target writes`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = false)

        assertThrows<ProfessionalProjectRequestNotFoundException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `inactive applicant relationship produces conflict before target writes`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = false)

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("医生已不具备该机构的有效执业关系", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `vanished platform project produces not found before target writes`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = false)

        assertThrows<ProfessionalProjectRequestNotFoundException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `duplicate institution target produces conflict before writes`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = true)

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("该机构已存在此平台项目，请改为申请加入机构项目", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `platform rate drift produces conflict before writes`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1", driftedSplit = true))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)

        val error = assertThrows<ProfessionalProjectRequestConflictException> {
            service.reviewInstitution(adminActor(), "request-1", ProjectRequestReview("APPROVED"))
        }

        assertEquals("申请分账比例与当前平台规则冲突", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
        verify(exactly = 0) { cacheManager.getCache(any()) }
    }

    @Test
    fun `institution approval creates institution project binds doctor and closes request`() {
        stubLockedRequest(targetResultSet("INSTITUTION", "institution-1"))
        stubInstitutionLock(found = true)
        stubRelationshipLock(found = true)
        stubCurrentProject(found = true)
        stubInstitutionProjectLookup(found = false)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val actor = legalRepresentativeActor(setOf("institution-1"))
        val result = service.reviewInstitution(
            actor,
            "request-1",
            ProjectRequestReview("APPROVED")
        )

        assertEquals("APPROVED", result.status)
        verify(exactly = 1) {
            jdbcTemplate.update(match<String> { it.contains("INSERT INTO institution_projects") }, *anyVararg())
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> { it.contains("INSERT INTO doctor_projects") && it.contains("price") },
                "doctor-1", "project-1", any(), "Service", "tag", "", "cover.png", "one.png", BigDecimal("99.00")
            )
        }
        verify(exactly = 1) {
            reviewAuthority.requireCurrentAuthority(actor, "institution-1")
        }
        verify(exactly = 1) {
            jdbcTemplate.update(match<String> { it.contains("UPDATE professional_project_requests") }, *anyVararg())
        }
        verify(exactly = 1) {
            businessNotifications.institutionProjectApplicationApproved("doctor-1", "request-1")
        }
    }

    private fun stubLockedRequest(row: ResultSet) {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("professional_project_requests") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(row, 0))
        }
    }

    private fun stubInstitutionLock(found: Boolean) {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                *anyVararg()
            )
        } returns if (found) listOf("institution-1") else emptyList()
    }

    private fun stubRelationshipLock(found: Boolean) {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM doctor_institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                *anyVararg()
            )
        } returns if (found) listOf("relationship-1") else emptyList()
    }

    private fun stubCurrentProject(found: Boolean) {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        } answers {
            if (!found) emptyList() else {
                val mapper = secondArg<RowMapper<Any>>()
                listOf(mapper.mapRow(platformProjectResultSet(), 0))
            }
        }
    }

    private fun stubInstitutionProjectLookup(found: Boolean) {
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("FROM institution_projects") },
                String::class.java,
                *anyVararg()
            )
        } returns if (found) listOf("institution-project-1") else emptyList()
    }

    private fun platformProjectResultSet(): ResultSet = mockk<ResultSet>(relaxed = true).also { rs ->
        every { rs.getString("id") } returns "project-1"
        every { rs.getString("name") } returns "Base Project"
        every { rs.getString("category") } returns "Base Category"
        every { rs.getString("description") } returns "Base Description"
        every { rs.getString("tags") } returns "base-tag"
        every { rs.getString("category_tags") } returns "base-category"
        every { rs.getString("cover_image") } returns "base-cover.png"
        every { rs.getString("images") } returns "base-one.png,base-two.png"
        every { rs.getBigDecimal("reference_price") } returns BigDecimal("199.00")
        every { rs.getString("currency") } returns "CNY"
        every { rs.getString("slogan") } returns "Base Slogan"
        every { rs.getString("detail_content") } returns "Base details"
        every { rs.getInt("sales_count") } returns 5
    }

    private fun targetResultSet(
        type: String,
        institutionId: String?,
        status: String = "PENDING",
        driftedSplit: Boolean = false,
        tagsJson: String = "[\"tag\"]",
        categoryTagsJson: String = "[\"category-tag\"]",
        imagesJson: String = "[\"one.png\"]",
        currency: String = "USD"
    ): ResultSet = mockk<ResultSet>(relaxed = true).also { rs ->
        every { rs.getString("id") } returns "request-1"
        every { rs.getString("request_type") } returns type
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns institutionId
        every { rs.getString("project_id") } returns "project-1"
        every { rs.getString("name") } returns "Project"
        every { rs.getString("category") } returns "Category"
        every { rs.getString("description") } returns "Service"
        every { rs.getString("tags") } returns tagsJson
        every { rs.getString("slogan") } returns "Slogan"
        every { rs.getString("detail_content") } returns "Service details"
        every { rs.getString("currency") } returns currency
        every { rs.getString("cover_image") } returns "cover.png"
        every { rs.getString("images") } returns imagesJson
        every { rs.getInt("sales_count") } returns 1
        every { rs.getBigDecimal("reference_price") } returns BigDecimal("199.00")
        every { rs.getString("category_tags") } returns categoryTagsJson
        every { rs.getBigDecimal("price") } returns BigDecimal("99.00")
        every { rs.getBigDecimal("original_price") } returns null
        every { rs.getBoolean("is_active") } returns true
        every { rs.getObject("is_active") } returns true
        every { rs.getBigDecimal("consultation_fee") } returns BigDecimal("10.00")
        every { rs.getBigDecimal("commission_rate") } returns BigDecimal(if (driftedSplit) "40.00" else "20.00")
        every { rs.getBigDecimal("institution_rate") } returns BigDecimal("30.00")
        every { rs.getString("notes") } returns "Notes"
        every { rs.getString("status") } returns status
    }

    private fun stubListRows(vararg rows: ResultSet) {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            rows.mapIndexed { index, rs -> mapper.mapRow(rs, index) }
        }
    }

    private fun assertDecimal(expected: String, actual: JsonNode) {
        assertEquals(0, actual.decimalValue().compareTo(BigDecimal(expected)))
    }

    private fun listResultSet(
        type: String,
        legacy: Boolean = false,
        driftedSplit: Boolean = false,
        inheritanceSentinels: Boolean = false
    ): ResultSet = mockk<ResultSet>(relaxed = true).also { rs ->
        val strings = mapOf(
            "id" to "request-1",
            "request_type" to type,
            "doctor_id" to "doctor-1",
            "doctor_name" to "Doctor One",
            "institution_id" to if (type == "INSTITUTION") "institution-1" else null,
            "institution_name" to if (type == "INSTITUTION") "Institution One" else null,
            "project_id" to if (type == "INSTITUTION") "project-1" else null,
            "project_name" to if (type == "INSTITUTION") "Base project" else null,
            "name" to if (type == "PLATFORM") "Platform snapshot" else "Institution override",
            "category" to "SKIN",
            "description" to "Snapshot description",
            "tags" to if (legacy || inheritanceSentinels) null else "[\"laser\",\"skin\"]",
            "slogan" to if (legacy) null else if (inheritanceSentinels) "" else "Clear today",
            "detail_content" to if (legacy) null else "Snapshot details",
            "currency" to if (legacy) null else "CNY",
            "cover_image" to if (legacy) null else if (inheritanceSentinels) "" else "cover.png",
            "images" to if (legacy || inheritanceSentinels) null else "[\"one.png\",\"two.png\"]",
            "category_tags" to if (legacy || type == "INSTITUTION") null else "[\"face\"]",
            "notes" to "Notes",
            "status" to "PENDING",
            "review_note" to "Reviewed",
            "reviewed_by" to "reviewer-1",
            "resulting_project_id" to "result-project",
            "resulting_institution_project_id" to "result-institution-project"
        )
        val decimals = mapOf(
            "reference_price" to if (legacy || type == "INSTITUTION") null else BigDecimal("199.90"),
            "price" to if (type == "INSTITUTION") BigDecimal("99.00") else null,
            "original_price" to if (type == "INSTITUTION") BigDecimal("120.00") else null,
            "consultation_fee" to if (type == "INSTITUTION") BigDecimal("10.00") else null,
            "commission_rate" to if (type == "INSTITUTION") BigDecimal(if (driftedSplit) "30.00" else "20.00") else null,
            "institution_rate" to if (type == "INSTITUTION") BigDecimal(if (driftedSplit) "40.00" else "30.00") else null
        )
        val submitted = LocalDateTime.of(2026, 8, 16, 10, 0)
        val updated = submitted.plusHours(1)
        val reviewed = submitted.plusMinutes(30)

        every { rs.getString(any<String>()) } answers { strings[firstArg()] }
        every { rs.getBigDecimal(any<String>()) } answers { decimals[firstArg()] }
        every { rs.getInt("sales_count") } returns if (legacy) 0 else 8
        every { rs.getBoolean("is_active") } returns (type == "INSTITUTION")
        every { rs.getObject("is_active") } returns if (type == "INSTITUTION") true else null
        every { rs.getTimestamp("submitted_at") } returns Timestamp.valueOf(submitted)
        every { rs.getTimestamp("updated_at") } returns Timestamp.valueOf(updated)
        every { rs.getTimestamp("reviewed_at") } returns Timestamp.valueOf(reviewed)
    }

    private fun doctorActor(approvedInstitutions: Set<String> = setOf("institution-1")) = ManagementActor(
        "doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), approvedInstitutions, setOf("doctor-1")
    )

    private fun legalRepresentativeActor(institutions: Set<String>) = ManagementActor(
        "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, institutions, emptySet(), emptySet()
    )

    private fun adminActor() = ManagementActor(
        "admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet()
    )
}
