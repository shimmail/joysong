package com.joysong.server.institution.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.BaseResponse
import com.joysong.server.common.GlobalExceptionHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class DoctorProjectSnapshotCodecTest {
    private val mapper = jacksonObjectMapper()
    private val codec = DoctorProjectSnapshotCodec(mapper)

    @Test
    fun `response omits null error code and coded domain errors expose it`() {
        val success = mapper.readTree(mapper.writeValueAsString(BaseResponse.success("ok")))
        val legacyError = mapper.readTree(mapper.writeValueAsString(BaseResponse.error<Nothing>("bad")))
        assertFalse(success.has("errorCode"))
        assertFalse(legacyError.has("errorCode"))

        val response = GlobalExceptionHandler().handleProjectChangeContract(
            ProjectChangeContractException(
                HttpStatus.CONFLICT,
                ProjectChangeErrorCode.EDIT_BASE_STALE,
                "stale"
            )
        )
        assertEquals(409, response.statusCode.value())
        assertEquals("EDIT_BASE_STALE", response.body?.errorCode)
        assertEquals("EDIT_BASE_STALE", mapper.readTree(mapper.writeValueAsString(response.body)).path("errorCode").asText())
    }

    @Test
    fun `stable project change error code set is complete`() {
        assertEquals(
            setOf(
                "EDIT_BASE_STALE", "APPROVAL_BASE_STALE", "INHERITANCE_SOURCE_STALE",
                "PRICING_POLICY_STALE", "FORCE_BASE_STALE", "REQUEST_ALREADY_PENDING",
                "REQUEST_ALREADY_HANDLED", "CLIENT_UPGRADE_REQUIRED", "FORCE_NOT_APPLICABLE",
                "PROJECT_PAYLOAD_INVALID", "REQUEST_SNAPSHOT_INVALID",
                "INSTITUTION_PROJECT_VERSION_STALE"
            ),
            ProjectChangeErrorCode.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun `canonical hashes match the frozen documented vectors`() {
        val platformHash = codec.platformInheritanceHash(
            PlatformInheritanceSource(
                platformProjectId = "pp-1",
                name = "Face Lift",
                category = "Surgery",
                description = null,
                tags = "[\"face\",\"lift\"]",
                slogan = "Natural result",
                detailContent = "Detail",
                coverImage = "https://cdn.example/cover.jpg",
                images = "[\"https://cdn.example/1.jpg\"]"
            )
        )
        assertEquals("f4168ca8ed8e9d41b62aef5f215a78cd2c018e1a3e8b7384f4311216fdeaea81", platformHash)

        assertEquals(
            "314a4110fbe7a30bbd69054b85e25fd0f6b5c646dce0f5df35d1f2913ed635f1",
            codec.baseRevision(
                DoctorProjectRevisionSource(
                    institutionProjectId = "ip-1",
                    institutionId = "inst-1",
                    platformProjectId = "pp-1",
                    institutionProjectVersion = 7,
                    platformInheritanceHash = platformHash,
                    doctorProjectUpdatedAt = Instant.parse("2026-08-24T01:02:03.456Z"),
                    configId = null,
                    configUpdatedAt = null,
                    pricingPolicyRevision = "travel-ground-service-rate:0.400000"
                )
            )
        )
    }

    @Test
    fun `canonicalization uses NFC compatible list decoding and preserves order duplicates nulls and instant precision`() {
        val composed = platformSource(name = "Caf\u00e9", tags = " first , first , second ")
        val decomposed = platformSource(name = "Cafe\u0301", tags = "[\"first\",\"first\",\"second\"]")
        assertEquals(codec.platformInheritanceHash(composed), codec.platformInheritanceHash(decomposed))
        assertNotEquals(
            codec.platformInheritanceHash(platformSource(tags = "a,a,b")),
            codec.platformInheritanceHash(platformSource(tags = "a,b,a"))
        )
        assertNotEquals(
            codec.platformInheritanceHash(platformSource(description = null)),
            codec.platformInheritanceHash(platformSource(description = "value"))
        )

        val precise = revisionSource(Instant.parse("2026-08-24T01:02:03.456789Z"))
        assertNotEquals(codec.baseRevision(precise), codec.baseRevision(revisionSource(Instant.parse("2026-08-24T01:02:03.456Z"))))
    }

    @Test
    fun `snapshot round trip is strict and retains explicit nulls`() {
        val snapshot = validSnapshot()
        val encoded = codec.encode(snapshot)
        val decoded = codec.decode(encoded)
        assertEquals(snapshot, decoded)
        assertTrue(mapper.readTree(encoded).path("rawOverrides").has("description"))
        assertNull(decoded.rawOverrides.description)
    }

    @Test
    fun `snapshot decoder fails closed for malformed schemas and exact key violations`() {
        val valid = codec.encode(validSnapshot())
        val cases = listOf(
            "not-json",
            valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
            valid.replaceFirst("\"association\":", "\"extra\":true,\"association\":"),
            valid.replaceFirst("\"institutionId\":\"inst-1\",", ""),
            valid.replace("\"tags\":[\"local\"]", "\"tags\":[]"),
            valid.replace("\"name\":\"Effective name\"", "\"name\":\"   \"")
        )
        cases.forEach { raw -> assertThrows<IllegalArgumentException> { codec.decode(raw) } }
    }

    @Test
    fun `root golden fixture freezes outer and nested v2 contracts`() {
        val fixture = mapper.readTree(Files.readString(Path.of("..", "test-fixtures", "doctor-project-change-v2.json")))
        assertEquals(setOf("targetV2", "requestV1", "requestV2", "requestV2InvalidSnapshot"), fixture.fieldNames().asSequence().toSet())
        assertEquals(
            setOf(
                "payloadVersion", "institutionProjectId", "institutionId", "institutionName", "platformProjectId",
                "platformProjectName", "doctorId", "doctorName", "baseRevision", "currentProject",
                "currentDoctorPrice", "currentDoctorActive", "platformRate", "pricingPolicyRevision",
                "travelGroundServiceFee"
            ),
            fixture.path("targetV2").fieldNames().asSequence().toSet()
        )
        assertEquals(
            setOf(
                "payloadVersion", "id", "doctorId", "doctorName", "institutionId", "institutionName",
                "institutionProjectId", "projectName", "requestType", "serviceDescription", "priceSuggestion",
                "notes", "serviceTags", "scheduleNote", "coverImage", "images", "consultationFee",
                "commissionRate", "institutionRate", "medicalListPrice", "platformRate", "doctorRate",
                "forceProcessed", "currentPrice", "currentServiceDescription", "currentServiceTags",
                "currentScheduleNote", "currentCoverImage", "currentImages", "currentConsultationFee",
                "currentMedicalListPrice", "currentCommissionRate", "currentInstitutionRate",
                "currentPlatformRate", "currentDoctorRate", "status", "submittedBy", "reviewedBy",
                "reviewerName", "reviewNote", "submittedAt", "reviewedAt", "updatedAt"
            ),
            fixture.path("requestV1").fieldNames().asSequence().toSet()
        )
        val requestV2Keys = setOf(
            "payloadVersion", "id", "requestType", "doctorId", "doctorName", "institutionId", "institutionName",
            "institutionProjectId", "institutionProjectName", "platformProjectId", "platformProjectName",
            "baseRevision", "currentProject", "proposedProject", "latestProject", "latestRevision", "sharedChanged",
            "currentDoctorPrice", "proposedDoctorPrice", "latestDoctorPrice", "currentDoctorActive",
            "proposedDoctorActive", "latestDoctorActive", "platformRate", "pricingPolicyRevision",
            "travelGroundServiceFee", "requestStatus", "notes", "forceProcessed", "submittedBy", "submittedAt",
            "reviewedBy", "reviewerName", "reviewNote", "reviewedAt", "updatedAt", "snapshotState", "snapshotError",
            "reviewable"
        )
        assertEquals(requestV2Keys, fixture.path("requestV2").fieldNames().asSequence().toSet())
        assertEquals(requestV2Keys, fixture.path("requestV2InvalidSnapshot").fieldNames().asSequence().toSet())
        assertEquals("VALID", fixture.path("requestV2").path("snapshotState").asText())
        assertTrue(fixture.path("requestV2").path("snapshotError").isNull)
        assertFalse(fixture.path("requestV2InvalidSnapshot").path("reviewable").asBoolean())
        assertTrue(fixture.path("requestV2InvalidSnapshot").path("snapshotError").asText().isNotBlank())
        assertTrue(fixture.path("requestV2InvalidSnapshot").path("currentProject").isNull)
        listOf("currentProject", "proposedProject", "latestProject").forEach { key ->
            codec.decode(mapper.writeValueAsString(fixture.path("requestV2").path(key)))
        }
    }

    private fun platformSource(
        name: String = "Name",
        description: String? = null,
        tags: String? = "a,b"
    ) = PlatformInheritanceSource(
        platformProjectId = "pp", name = name, category = "Category", description = description,
        tags = tags, slogan = null, detailContent = null, coverImage = null, images = null
    )

    private fun revisionSource(instant: Instant) = DoctorProjectRevisionSource(
        institutionProjectId = "ip", institutionId = "inst", platformProjectId = "pp",
        institutionProjectVersion = 1, platformInheritanceHash = "a".repeat(64),
        doctorProjectUpdatedAt = instant, configId = null, configUpdatedAt = null,
        pricingPolicyRevision = "travel-ground-service-rate:0.400000"
    )

    private fun validSnapshot() = InstitutionProjectSnapshotV2(
        schemaVersion = 2,
        association = ProjectAssociationSnapshot("ip-1", "inst-1", "pp-1"),
        rawOverrides = ProjectRawOverridesSnapshot(
            name = "Local name", category = null, description = null, tags = listOf("local"),
            slogan = null, detailContent = null, coverImage = null, images = listOf("image")
        ),
        effective = ProjectEffectiveSnapshot(
            name = "Effective name", category = "Category", description = null, tags = listOf("local"),
            slogan = null, detailContent = null, salesCount = 0, coverImage = null, images = listOf("image")
        ),
        source = ProjectSnapshotSource(7, "a".repeat(64))
    )
}
