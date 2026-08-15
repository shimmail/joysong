package com.joysong.server.project.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.money.CurrencyCode
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
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
    private val service = ProfessionalProjectRequestService(jdbcTemplate, objectMapper, splitRatePolicy)

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
                currency = CurrencyCode.CNY,
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
                "CNY", "cover.png", "[\"one.png\",\"two.png\"]", 12,
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
                name = " ", category = null, description = " ", tags = null, slogan = " ",
                detailContent = " ", coverImage = null, images = null,
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
    fun `submission rejects invalid money counts arrays and bounded text before insert`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1
        val invalidPlatformRequests = listOf(
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
    fun `institution submission validates every amount and delegated split rates`() {
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM projects") }, Long::class.java, *anyVararg()) } returns 1L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("FROM institution_projects") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.queryForObject(match<String> { it.contains("professional_project_requests") }, Long::class.java, *anyVararg()) } returns 0L
        every { jdbcTemplate.update(match<String> { it.contains("INSERT INTO professional_project_requests") }, *anyVararg()) } returns 1
        val invalidRequests = listOf(
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", price = BigDecimal("1.001")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", originalPrice = BigDecimal("-0.01")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", consultationFee = BigDecimal("100000000.00")),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", salesCount = -1),
            DoctorInstitutionProjectRequest(projectId = "project-1", description = "Service", commissionRate = BigDecimal("60.00"), institutionRate = BigDecimal("50.00"))
        )

        invalidRequests.forEach { request ->
            assertThrows<IllegalArgumentException> {
                service.submitInstitution(doctorActor(), "institution-1", request)
            }
        }
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
    fun `rejected and changes requested decisions require review note before database access`() {
        listOf("REJECTED", "CHANGES_REQUESTED").forEach { decision ->
            val error = assertThrows<IllegalArgumentException> {
                service.reviewPlatform(adminActor(), "request-1", ProjectRequestReview(decision, "  "))
            }
            assertEquals("拒绝或要求修改时必须填写审核意见", error.message)
        }
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `institution reviewer cannot review another institution request`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-2"), 0))
        }

        val error = assertThrows<AccessDeniedException> {
            service.reviewInstitution(
                legalRepresentativeActor(setOf("institution-1")),
                "request-1",
                ProjectRequestReview("APPROVED")
            )
        }

        assertEquals("只能审核本机构的项目申请", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `institution approval creates institution project binds doctor and closes request`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-1"), 0))
        }
        every { jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg()) } answers {
            if (firstArg<String>().contains("institution_projects")) 0L else 1L
        }
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns "institution-1"
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.reviewInstitution(
            legalRepresentativeActor(setOf("institution-1")),
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
                "doctor-1", "project-1", any(), "Service", "", BigDecimal("99.00")
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(match<String> { it.contains("UPDATE professional_project_requests") }, *anyVararg())
        }
    }

    @Test
    fun `institution approval revalidates current doctor membership`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(targetResultSet("INSTITUTION", "institution-1"), 0))
        }
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("FROM institutions") && it.contains("FOR UPDATE") },
                String::class.java,
                "institution-1"
            )
        } returns "institution-1"
        every { jdbcTemplate.queryForObject(match<String> { it.contains("doctor_institutions") }, Long::class.java, *anyVararg()) } returns 0L

        val error = assertThrows<IllegalArgumentException> {
            service.reviewInstitution(
                legalRepresentativeActor(setOf("institution-1")),
                "request-1",
                ProjectRequestReview("APPROVED")
            )
        }

        assertEquals("医生已不具备该机构的有效执业关系", error.message)
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("INSERT INTO institution_projects") }, *anyVararg()) }
    }

    private fun targetResultSet(type: String, institutionId: String?): ResultSet = mockk<ResultSet>(relaxed = true).also { rs ->
        every { rs.getString("id") } returns "request-1"
        every { rs.getString("request_type") } returns type
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns institutionId
        every { rs.getString("project_id") } returns "project-1"
        every { rs.getString("name") } returns "Project"
        every { rs.getString("category") } returns "Category"
        every { rs.getString("description") } returns "Service"
        every { rs.getString("tags") } returns "[\"tag\"]"
        every { rs.getString("slogan") } returns "Slogan"
        every { rs.getString("detail_content") } returns "Service details"
        every { rs.getString("currency") } returns "USD"
        every { rs.getString("cover_image") } returns "cover.png"
        every { rs.getString("images") } returns "[\"one.png\"]"
        every { rs.getInt("sales_count") } returns 1
        every { rs.getString("category_tags") } returns null
        every { rs.getBigDecimal("price") } returns BigDecimal("99.00")
        every { rs.getBoolean("is_active") } returns true
        every { rs.getObject("is_active") } returns true
        every { rs.getBigDecimal("consultation_fee") } returns BigDecimal("10.00")
        every { rs.getBigDecimal("commission_rate") } returns BigDecimal("20.00")
        every { rs.getBigDecimal("institution_rate") } returns BigDecimal("30.00")
        every { rs.getString("notes") } returns "Notes"
        every { rs.getString("status") } returns "PENDING"
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
