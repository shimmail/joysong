package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityOperations
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.project.service.InstitutionProjectPayloadPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.Instant
import java.util.TimeZone
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.config.OrderSplitProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.cache.CacheManager
import org.springframework.cache.Cache
import org.springframework.transaction.support.TransactionSynchronizationManager

class DoctorProjectChangeServiceTest {
    private val jdbcTemplate = mockk<JdbcTemplate>()
    private val doctorProjectRepository = mockk<DoctorProjectRepository>()
    private val institutionProjectRepository = mockk<InstitutionProjectRepository>(relaxUnitFun = true)
    private val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
    private val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
    private val reviewAuthority = mockk<InstitutionRelationshipReviewAuthorityOperations>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val splitRatePolicy = OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("10.00"); institutionRate = BigDecimal("40.00") })
    private val travelGroundServicePricing = TravelGroundServicePricing(
        OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") })
    )
    private val payloadPolicy = spyk(InstitutionProjectPayloadPolicy())
    private val objectMapper = jacksonObjectMapper()
    private val service = DoctorProjectChangeService(
        jdbcTemplate, institutionProjectRepository, doctorProjectRepository, configRepository,
        relationshipService, splitRatePolicy, travelGroundServicePricing, payloadPolicy, objectMapper,
        reviewAuthority, cacheManager
    )

    @Test
    fun `v2 review rejects force for a non approval decision before touching storage`() {
        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(
                adminActor(),
                "request-1",
                DoctorProjectReviewV2Command(
                    decision = ProjectChangeDecision.REJECTED,
                    reviewNote = "reason",
                    force = true,
                    forceBaseRevision = "a".repeat(64)
                )
            )
        }

        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        assertEquals(ProjectChangeErrorCode.FORCE_NOT_APPLICABLE, error.errorCode)
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `v2 review denies legal representative force before touching storage`() {
        assertThrows(AccessDeniedException::class.java) {
            service.reviewV2(
                legalActor(),
                "request-1",
                DoctorProjectReviewV2Command(
                    decision = ProjectChangeDecision.APPROVED,
                    reviewNote = "force reason",
                    force = true,
                    forceBaseRevision = "a".repeat(64)
                )
            )
        }

        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `v2 review rejects force for a locked legacy row without changing v1 compatibility`() {
        stubReviewQueries()

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(
                adminActor(),
                "request-1",
                DoctorProjectReviewV2Command(
                    decision = ProjectChangeDecision.APPROVED,
                    reviewNote = "force reason",
                    force = true,
                    forceBaseRevision = "a".repeat(64)
                )
            )
        }

        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        assertEquals(ProjectChangeErrorCode.FORCE_NOT_APPLICABLE, error.errorCode)
        verify(exactly = 0) { institutionProjectRepository.findForUpdate(any()) }
    }

    @Test
    fun `v2 reviews a legacy join and flushes business writes before final status`() {
        stubReviewQueries()
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { institutionProjectRepository.flush() } returns Unit
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.reviewV2(legalActor(), "request-1", DoctorProjectReviewV2Command(
            ProjectChangeDecision.APPROVED,
            "",
            false,
            null
        ))

        assertEquals("APPROVED", (result as LegacyDoctorProjectChangeViewV2).status)
        verifyOrder {
            doctorProjectRepository.save(any())
            institutionProjectRepository.flush()
            jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg())
        }
    }

    @Test
    fun `v2 reject requires a note before touching business storage`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.reviewV2(
                legalActor(),
                "request-1",
                DoctorProjectReviewV2Command(
                    decision = ProjectChangeDecision.REJECTED,
                    reviewNote = " ",
                    force = false,
                    forceBaseRevision = null
                )
            )
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { institutionProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
    }

    @Test
    fun `legacy review refuses a payload v2 row with client upgrade error`() {
        stubReviewQueries(payloadVersion = 2)

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        assertEquals(org.springframework.http.HttpStatus.UPGRADE_REQUIRED, error.status)
        assertEquals(ProjectChangeErrorCode.CLIENT_UPGRADE_REQUIRED, error.errorCode)
        verify(exactly = 0) { institutionProjectRepository.findForUpdate(any()) }
    }

    @Test
    fun `legacy withdraw refuses a payload v2 row with client upgrade error`() {
        stubReviewQueries(payloadVersion = 2)

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.withdraw(doctorActor(), "request-1")
        }

        assertEquals(org.springframework.http.HttpStatus.UPGRADE_REQUIRED, error.status)
        assertEquals(ProjectChangeErrorCode.CLIENT_UPGRADE_REQUIRED, error.errorCode)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `review revalidates legal authority after locking request and writes nothing when revoked`() {
        stubReviewQueries()
        every { reviewAuthority.requireCurrentAuthority(legalActor(), "institution-1") } throws
            AccessDeniedException("revoked")

        assertThrows(AccessDeniedException::class.java) {
            service.reviewV2(
                legalActor(),
                "request-1",
                DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
            )
        }

        verify(exactly = 0) { institutionProjectRepository.findForUpdate(any()) }
        verify(exactly = 0) { doctorProjectRepository.findForUpdate(any(), any()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `review returns typed handled conflict for withdrawn row`() {
        stubReviewQueries(targetStatus = "WITHDRAWN")

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(
                legalActor(),
                "request-1",
                DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
            )
        }

        assertEquals(ProjectChangeErrorCode.REQUEST_ALREADY_HANDLED, error.errorCode)
        verify(exactly = 0) { reviewAuthority.requireCurrentAuthority(any(), any()) }
    }

    @Test
    fun `approved review clears project caches only after transaction commit`() {
        stubReviewQueries()
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1
        val caches = listOf(mockk<Cache>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        every { cacheManager.getCache("discover") } returns caches[0]
        every { cacheManager.getCache("home") } returns caches[1]
        every { cacheManager.getCache("projects") } returns caches[2]
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.review(legalActor(), "request-1", "APPROVED", "", false)

            caches.forEach { verify(exactly = 0) { it.clear() } }
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            caches.forEach { verify(exactly = 1) { it.clear() } }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `approved review never clears project caches without an after commit callback`() {
        stubReviewQueries()
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1
        val caches = listOf(mockk<Cache>(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        every { cacheManager.getCache("discover") } returns caches[0]
        every { cacheManager.getCache("home") } returns caches[1]
        every { cacheManager.getCache("projects") } returns caches[2]

        service.review(legalActor(), "request-1", "APPROVED", "", false)

        caches.forEach { verify(exactly = 0) { it.clear() } }
    }

    @Test
    fun `v2 targets expose raw effective inactive doctor state and canonical pricing`() {
        stubV2TargetQuery(doctorActive = false)

        val target = service.listProfileUpdateTargetsV2(doctorActor()).single()

        assertEquals(2, target.payloadVersion)
        assertEquals("ip-1", target.institutionProjectId)
        assertEquals("institution-1", target.institutionId)
        assertEquals("Institution", target.institutionName)
        assertEquals("project-1", target.platformProjectId)
        assertEquals("Platform Project", target.platformProjectName)
        assertEquals("Local Project", target.currentProject.rawOverrides.name)
        assertEquals("Platform Category", target.currentProject.effective.category)
        assertEquals(listOf("platform-tag"), target.currentProject.effective.tags)
        assertEquals(false, target.currentDoctorActive)
        assertEquals(BigDecimal("100.00"), target.currentDoctorPrice)
        assertEquals(BigDecimal("40.00"), target.platformRate)
        assertEquals("travel-ground-service-rate:0.400000", target.pricingPolicyRevision)
        assertEquals(BigDecimal("40.00"), target.travelGroundServiceFee)
        assertEquals(64, target.baseRevision.length)
        verify {
            jdbcTemplate.query(
                match<String> { sql ->
                    sql.contains("FROM doctor_projects dp") &&
                        !sql.contains("dp.is_active = TRUE") &&
                        !sql.contains("dp.is_active=TRUE")
                },
                any<RowMapper<Any>>(),
                "doctor-1"
            )
        }
    }

    @Test
    fun `submit v2 retains inheritance intent calls shared payload policy and maps price to ledger`() {
        val baseRevision = stubV2SubmitState(currentName = null)
        every { jdbcTemplate.update(match<String> { it.contains("payload_version") }, *anyVararg()) } returns 1

        val result = service.submitV2(
            doctorActor(),
            v2Request(
                baseRevision = baseRevision,
                name = null,
                category = null,
                description = null,
                tags = null,
                slogan = null,
                detailContent = null,
                coverImage = null,
                images = null,
                price = BigDecimal("120.00"),
                doctorActive = false
            )
        ) as VersionedDoctorProjectChangeViewV2

        assertEquals(2, result.payloadVersion)
        assertEquals("Institution", result.institutionName)
        assertEquals("Doctor", result.doctorName)
        assertEquals(false, result.sharedChanged)
        assertEquals(null, result.proposedProject?.rawOverrides?.category)
        assertEquals("Platform Category", result.proposedProject?.effective?.category)
        assertEquals(BigDecimal("120.00"), result.proposedDoctorPrice)
        assertEquals(false, result.proposedDoctorActive)
        verify(exactly = 1) { payloadPolicy.normalize(any()) }
        verify {
            jdbcTemplate.update(
                match<String> { sql ->
                    sql.contains("medical_list_price") &&
                        sql.contains("current_price") &&
                        !sql.contains("price_suggestion") &&
                        !sql.contains("schedule_note") &&
                        !sql.contains("current_schedule_note")
                },
                *anyVararg()
            )
        }
    }

    @Test
    fun `target revision remains valid when submit rereads unchanged database timestamps in Asia Shanghai`() {
        val originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            stubV2TargetQuery(doctorActive = true)
            val baseRevision = service.listProfileUpdateTargetsV2(doctorActor()).single().baseRevision
            stubV2SubmitState()
            every { jdbcTemplate.update(match<String> { it.contains("payload_version") }, *anyVararg()) } returns 1

            val result = service.submitV2(doctorActor(), v2Request(baseRevision = baseRevision))

            assertEquals(baseRevision, (result as VersionedDoctorProjectChangeViewV2).baseRevision)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun `submit v2 empty values clear overrides back to inheritance`() {
        val baseRevision = stubV2SubmitState(currentName = "Local Project", currentTags = "local-tag")
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.submitV2(
            doctorActor(),
            v2Request(
                baseRevision = baseRevision,
                name = "  ",
                tags = emptyList(),
                images = emptyList()
            )
        ) as VersionedDoctorProjectChangeViewV2

        assertEquals(null, result.proposedProject?.rawOverrides?.name)
        assertEquals(null, result.proposedProject?.rawOverrides?.tags)
        assertEquals("Platform Project", result.proposedProject?.effective?.name)
        assertEquals(listOf("platform-tag"), result.proposedProject?.effective?.tags)
        assertEquals(true, result.sharedChanged)
    }

    @Test
    fun `submit v2 preserves explicit override equal to platform value`() {
        val baseRevision = stubV2SubmitState(currentName = null)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val result = service.submitV2(
            doctorActor(),
            v2Request(baseRevision = baseRevision, name = "Platform Project")
        ) as VersionedDoctorProjectChangeViewV2

        assertEquals("Platform Project", result.proposedProject?.rawOverrides?.name)
        assertEquals("Platform Project", result.proposedProject?.effective?.name)
        assertEquals(true, result.sharedChanged)
    }

    @Test
    fun `submit v2 classifies shared and pure private changes independently`() {
        val privateBase = stubV2SubmitState()
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1

        val privateResult = service.submitV2(
            doctorActor(),
            v2Request(baseRevision = privateBase, price = BigDecimal("130.00"), doctorActive = false)
        ) as VersionedDoctorProjectChangeViewV2
        assertEquals(false, privateResult.sharedChanged)

        val sharedBase = stubV2SubmitState()
        val sharedResult = service.submitV2(
            doctorActor(),
            v2Request(baseRevision = sharedBase, salesCount = 9)
        ) as VersionedDoctorProjectChangeViewV2
        assertEquals(true, sharedResult.sharedChanged)
    }

    @Test
    fun `two doctors can submit from the same shared project version independently`() {
        every { jdbcTemplate.update(match<String> { it.contains("payload_version") }, *anyVararg()) } returns 1
        val doctorOneBase = stubV2SubmitState(doctorId = "doctor-1", revisionVersion = 7)
        val doctorOne = service.submitV2(
            doctorActor("doctor-1"),
            v2Request(baseRevision = doctorOneBase, price = BigDecimal("110.00"))
        ) as VersionedDoctorProjectChangeViewV2
        val doctorTwoBase = stubV2SubmitState(doctorId = "doctor-2", revisionVersion = 7)
        val doctorTwo = service.submitV2(
            doctorActor("doctor-2"),
            v2Request(baseRevision = doctorTwoBase, price = BigDecimal("120.00"))
        ) as VersionedDoctorProjectChangeViewV2

        assertEquals("doctor-1", doctorOne.doctorId)
        assertEquals("doctor-2", doctorTwo.doctorId)
        assertEquals(7L, doctorOne.currentProject?.source?.institutionProjectVersion)
        assertEquals(7L, doctorTwo.currentProject?.source?.institutionProjectVersion)
    }

    @Test
    fun `submit v2 rejects missing required effective values with stable contract error`() {
        val baseRevision = stubV2SubmitState(platformCategory = "")

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.submitV2(doctorActor(), v2Request(baseRevision = baseRevision, category = null))
        }

        assertEquals(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, error.status)
        assertEquals(ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID, error.errorCode)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submit v2 denies revoked relationship before persistence`() {
        val baseRevision = stubV2SubmitState()
        every { relationshipService.requireActiveRelationshipForUpdate("doctor-1", "institution-1") } throws
            AccessDeniedException("revoked")

        assertThrows(AccessDeniedException::class.java) {
            service.submitV2(doctorActor(), v2Request(baseRevision = baseRevision))
        }

        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submit v2 denies missing doctor binding before persistence`() {
        val baseRevision = stubV2SubmitState(doctorProject = null)

        assertThrows(AccessDeniedException::class.java) {
            service.submitV2(doctorActor(), v2Request(baseRevision = baseRevision))
        }

        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submit v2 reports duplicate pending request with stable conflict code`() {
        val baseRevision = stubV2SubmitState(pendingCount = 1)

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.submitV2(doctorActor(), v2Request(baseRevision = baseRevision))
        }

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.status)
        assertEquals(ProjectChangeErrorCode.REQUEST_ALREADY_PENDING, error.errorCode)
    }

    @Test
    fun `submit v2 rejects exact base revision after any locked source drifts`() {
        val staleRevision = stubV2SubmitState(revisionVersion = 7, lockedVersion = 8)

        val error = assertThrows(ProjectChangeContractException::class.java) {
            service.submitV2(doctorActor(), v2Request(baseRevision = staleRevision))
        }

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.status)
        assertEquals(ProjectChangeErrorCode.EDIT_BASE_STALE, error.errorCode)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `submit v2 rejects doctor config and platform source drift independently`() {
        val staleDoctorRevision = stubV2SubmitState(
            doctorProject = doctorProject(updatedAt = LocalDateTime.of(2026, 8, 11, 10, 0))
        )
        assertEquals(
            ProjectChangeErrorCode.EDIT_BASE_STALE,
            assertThrows(ProjectChangeContractException::class.java) {
                service.submitV2(doctorActor(), v2Request(baseRevision = staleDoctorRevision))
            }.errorCode
        )

        val staleConfigRevision = stubV2SubmitState(
            lockedConfigUpdatedAt = LocalDateTime.of(2026, 8, 11, 10, 0)
        )
        assertEquals(
            ProjectChangeErrorCode.EDIT_BASE_STALE,
            assertThrows(ProjectChangeContractException::class.java) {
                service.submitV2(doctorActor(), v2Request(baseRevision = staleConfigRevision))
            }.errorCode
        )

        val stalePlatformRevision = stubV2SubmitState(
            platformCategory = "Changed Platform Category",
            revisionPlatformCategory = "Platform Category"
        )
        assertEquals(
            ProjectChangeErrorCode.EDIT_BASE_STALE,
            assertThrows(ProjectChangeContractException::class.java) {
                service.submitV2(doctorActor(), v2Request(baseRevision = stalePlatformRevision))
            }.errorCode
        )
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `v2 list adapts legacy rows and keeps damaged v2 snapshots visible but unreviewable`() {
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 1") }, any<RowMapper<Any>>())
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(viewResultSet("PENDING"), 0))
        }
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 2") }, any<RowMapper<Any>>())
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(
                mapper.mapRow(v2ListResultSet(id = "request-v2"), 0),
                mapper.mapRow(v2ListResultSet(id = "request-v2-broken", currentSnapshot = "{broken"), 1)
            )
        }

        val rows = service.listV2(legalActor())

        assertEquals(3, rows.size)
        val legacy = rows.single { it.id == "request-1" }
        assertEquals(1, legacy.payloadVersion)
        assertEquals(true, legacy is LegacyDoctorProjectChangeViewV2)
        val valid = rows.single { it.id == "request-v2" } as VersionedDoctorProjectChangeViewV2
        assertEquals("VALID", valid.snapshotState)
        assertEquals(true, valid.reviewable)
        assertEquals("Local Project", valid.currentProject?.effective?.name)
        val broken = rows.single { it.id == "request-v2-broken" } as VersionedDoctorProjectChangeViewV2
        assertEquals("INVALID", broken.snapshotState)
        assertEquals(ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID.name, broken.snapshotError)
        assertEquals(null, broken.currentProject)
        assertEquals(false, broken.reviewable)
    }

    @Test
    fun `v2 list keeps damaged outer ledger visible and preserves valid siblings`() {
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 1") }, any<RowMapper<Any>>())
        } returns emptyList()
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 2") }, any<RowMapper<Any>>())
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(
                mapper.mapRow(v2ListResultSet(id = "request-v2-valid"), 0),
                mapper.mapRow(
                    v2ListResultSet(
                        id = "request-v2-damaged-ledger",
                        currentPlatformRate = null,
                        travelGroundServiceFee = null
                    ),
                    1
                )
            )
        }

        val rows = service.listV2(legalActor())

        assertEquals(2, rows.size)
        assertEquals("VALID", (rows.single { it.id == "request-v2-valid" } as VersionedDoctorProjectChangeViewV2).snapshotState)
        val damaged = rows.single { it.id == "request-v2-damaged-ledger" } as VersionedDoctorProjectChangeViewV2
        assertEquals("INVALID", damaged.snapshotState)
        assertEquals(ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID.name, damaged.snapshotError)
        assertEquals(false, damaged.reviewable)
    }

    @Test
    fun `v2 list keeps immutable snapshots while refreshing latest state and force token after each drift`() {
        val baseRevision = stubV2SubmitState()
        var persistedLedger: CapturedV2Ledger? = null
        every { jdbcTemplate.update(match<String> { it.contains("payload_version") }, *anyVararg()) } answers {
            val values = invocation.args[1] as Array<*>
            persistedLedger = captureV2Ledger(values)
            1
        }
        val submitView = service.submitV2(
            doctorActor(),
            v2Request(
                baseRevision = baseRevision,
                name = "Proposed Project",
                price = BigDecimal("110.00"),
                doctorActive = false
            )
        ) as VersionedDoctorProjectChangeViewV2

        var liveState = V2LiveState(
            institutionProjectVersion = 7,
            doctorPrice = BigDecimal("100.00"),
            doctorActive = true,
            doctorUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0)),
            configId = "config-1",
            configUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        )
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 1") }, any<RowMapper<Any>>())
        } returns emptyList()
        every {
            jdbcTemplate.query(match<String> { it.contains("payload_version = 2") }, any<RowMapper<Any>>())
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(
                mapper.mapRow(
                    v2ListResultSet(
                        ledger = requireNotNull(persistedLedger),
                        liveState = liveState
                    ),
                    0
                )
            )
        }

        val initial = service.listV2(legalActor()).single() as VersionedDoctorProjectChangeViewV2
        liveState = liveState.copy(
            institutionProjectVersion = 8
        )
        val sharedOnly = service.listV2(legalActor()).single() as VersionedDoctorProjectChangeViewV2
        liveState = liveState.copy(
            doctorPrice = BigDecimal("125.00"),
            doctorActive = false,
            doctorUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 11, 10, 0))
        )
        val doctorPrivateOnly = service.listV2(legalActor()).single() as VersionedDoctorProjectChangeViewV2
        liveState = liveState.copy(
            configUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 12, 10, 0))
        )
        val configOnly = service.listV2(legalActor()).single() as VersionedDoctorProjectChangeViewV2

        assertEquals(submitView.id, initial.id)
        assertEquals(submitView.baseRevision, initial.baseRevision)
        assertEquals("doctor-1", initial.doctorId)
        assertEquals("institution-1", initial.institutionId)
        assertEquals("ip-1", initial.institutionProjectId)
        assertEquals(submitView.currentProject, initial.currentProject)
        assertEquals(submitView.proposedProject, initial.proposedProject)
        assertEquals(submitView.currentDoctorPrice, initial.currentDoctorPrice)
        assertEquals(submitView.currentDoctorActive, initial.currentDoctorActive)
        assertEquals(submitView.proposedDoctorPrice, initial.proposedDoctorPrice)
        assertEquals(submitView.proposedDoctorActive, initial.proposedDoctorActive)
        assertEquals(submitView.platformRate, initial.platformRate)
        assertEquals(submitView.travelGroundServiceFee, initial.travelGroundServiceFee)
        assertEquals(submitView.sharedChanged, initial.sharedChanged)
        assertEquals("notes", initial.notes)
        assertEquals("doctor-1", initial.submittedBy)
        listOf(sharedOnly, doctorPrivateOnly, configOnly).forEach { row ->
            assertEquals(initial.currentProject, row.currentProject)
            assertEquals(initial.proposedProject, row.proposedProject)
            assertEquals(initial.currentDoctorPrice, row.currentDoctorPrice)
            assertEquals(initial.currentDoctorActive, row.currentDoctorActive)
            assertEquals(initial.proposedDoctorPrice, row.proposedDoctorPrice)
            assertEquals(initial.proposedDoctorActive, row.proposedDoctorActive)
        }
        assertEquals(7L, initial.latestProject?.source?.institutionProjectVersion)
        assertEquals(BigDecimal("100.00"), initial.latestDoctorPrice)
        assertEquals(true, initial.latestDoctorActive)
        assertEquals(8L, sharedOnly.latestProject?.source?.institutionProjectVersion)
        assertEquals(BigDecimal("100.00"), sharedOnly.latestDoctorPrice)
        assertEquals(true, sharedOnly.latestDoctorActive)
        assertEquals(8L, doctorPrivateOnly.latestProject?.source?.institutionProjectVersion)
        assertEquals(BigDecimal("125.00"), doctorPrivateOnly.latestDoctorPrice)
        assertEquals(false, doctorPrivateOnly.latestDoctorActive)
        assertEquals(8L, configOnly.latestProject?.source?.institutionProjectVersion)
        assertEquals(BigDecimal("125.00"), configOnly.latestDoctorPrice)
        assertEquals(false, configOnly.latestDoctorActive)
        assertEquals(false, initial.latestRevision == sharedOnly.latestRevision)
        assertEquals(false, sharedOnly.latestRevision == doctorPrivateOnly.latestRevision)
        assertEquals(false, doctorPrivateOnly.latestRevision == configOnly.latestRevision)
    }

    @Test
    fun `profile update rejects different project and compatibility prices`() {
        stubProfileSubmission()

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(doctorActor(), profileRequest(
                priceSuggestion = BigDecimal("3999.00"),
                medicalListPrice = BigDecimal("4299.00")
            ))
        }

        assertEquals("医生项目价格与兼容价格必须一致", error.message)
    }

    @Test
    fun `profile approval writes one price to source and compatibility mirror`() {
        stubReviewQueries(
            requestType = "PROFILE_UPDATE",
            targetPriceSuggestion = BigDecimal("4299.00"),
            targetMedicalListPrice = BigDecimal("4299.00")
        )
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(
                id = "config-1",
                doctorId = "doctor-1",
                institutionProjectId = "ip-1",
                updatedAt = LocalDateTime.of(2026, 8, 10, 10, 0)
            )
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        service.review(legalActor(), "request-1", "APPROVED", "", false)

        verify { doctorProjectRepository.save(match { it.price == BigDecimal("4299.00") }) }
        verify { configRepository.save(match { it.medicalListPrice == BigDecimal("4299.00") }) }
    }

    @Test
    fun `profile update targets expose only current doctor approved active bindings`() {
        every {
            jdbcTemplate.query(
                match<String> {
                    it.contains("dp.doctor_id = ?") &&
                        it.contains("di.status = 'APPROVED'") &&
                        it.contains("di.revoked_at IS NULL") &&
                        it.contains("di.deleted_at IS NULL") &&
                        it.contains("ip.deleted_at IS NULL") &&
                        it.contains("c.doctor_id = dp.doctor_id") &&
                        it.contains("c.institution_project_id = dp.institution_project_id")
                },
                any<RowMapper<Any>>(),
                "doctor-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>(relaxed = true) {
                every { getString("institution_project_id") } returns "ip-1"
                every { getString("project_name") } returns "Project"
                every { getString("institution_id") } returns "institution-1"
                every { getString("institution_name") } returns "Institution"
                every { getBigDecimal("current_price") } returns BigDecimal("880.00")
                every { getString("service_description") } returns "service"
                every { getString("service_tags") } returns "tag-a,tag-b"
                every { getString("schedule_note") } returns "schedule"
                every { getString("cover_image") } returns "cover"
                every { getString("images") } returns "image-a,image-b"
                every { getBigDecimal("consultation_fee") } returns BigDecimal("30.00")
                every { getBigDecimal("commission_rate") } returns BigDecimal("10.00")
                every { getBigDecimal("institution_rate") } returns BigDecimal("40.00")
            }
            listOf(mapper.mapRow(rs, 0))
        }

        val target = service.listProfileUpdateTargets(doctorActor()).single()

        assertEquals("ip-1", target.institutionProjectId)
        assertEquals(BigDecimal("880.00"), target.currentPrice)
        assertEquals(listOf("tag-a", "tag-b"), target.serviceTags)
        assertEquals(BigDecimal("10.00"), target.platformRate)
        assertEquals(BigDecimal("40.00"), target.doctorRate)
    }

    @Test
    fun `profile update targets reject admin without authenticated doctor identity`() {
        assertThrows(AccessDeniedException::class.java) {
            service.listProfileUpdateTargets(adminActor())
        }
        verify(exactly = 0) { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `profile update target without config uses system defaults`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), "doctor-1") } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>(relaxed = true) {
                every { getString("institution_project_id") } returns "ip-1"
                every { getString("project_name") } returns "Project"
                every { getString("institution_id") } returns "institution-1"
                every { getString("institution_name") } returns "Institution"
                every { getBigDecimal("current_price") } returns BigDecimal("880.00")
                every { getBigDecimal("consultation_fee") } returns null
                every { getBigDecimal("commission_rate") } returns null
                every { getBigDecimal("institution_rate") } returns null
            }
            listOf(mapper.mapRow(rs, 0))
        }

        val target = service.listProfileUpdateTargets(doctorActor()).single()

        assertEquals(BigDecimal.ZERO, target.consultationFee)
        assertEquals(BigDecimal.ZERO, target.commissionRate)
        assertEquals(BigDecimal("40.00"), target.institutionRate)
        assertEquals(BigDecimal("10.00"), target.platformRate)
        assertEquals(BigDecimal("50.00"), target.doctorRate)
    }

    @Test
    fun `profile update requires complete snapshot values`() {
        val request = DoctorProjectChangeRequest(
            institutionProjectId = "ip-1", requestType = "PROFILE_UPDATE",
            serviceDescription = "service", priceSuggestion = BigDecimal("880.00"),
            notes = "notes", serviceTags = listOf("tag"), scheduleNote = "schedule",
            coverImage = "cover", images = listOf("image"), consultationFee = BigDecimal("30.00"),
            commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00"), medicalListPrice = BigDecimal("1000.00")
        )
        assertEquals(listOf("tag"), request.serviceTags)
        assertEquals(BigDecimal("30.00"), request.consultationFee)
        assertEquals(BigDecimal("1000.00"), request.medicalListPrice)
    }

    @Test
    fun `profile update request view exposes immutable before snapshot`() {
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = viewResultSet("PENDING")
            every { rs.getString("request_type") } returns "PROFILE_UPDATE"
            every { rs.getBigDecimal("current_price") } returns BigDecimal("700.00")
            every { rs.getString("current_service_description") } returns "before service"
            every { rs.getString("current_service_tags") } returns "before-a,before-b"
            every { rs.getString("current_schedule_note") } returns "before schedule"
            every { rs.getString("current_cover_image") } returns "before-cover"
            every { rs.getString("current_images") } returns "before-image"
            every { rs.getBigDecimal("current_consultation_fee") } returns BigDecimal("20.00")
            every { rs.getBigDecimal("current_medical_list_price") } returns BigDecimal("1000.00")
            every { rs.getBigDecimal("current_commission_rate") } returns BigDecimal("5.00")
            every { rs.getBigDecimal("current_institution_rate") } returns BigDecimal("35.00")
            every { rs.getBigDecimal("current_platform_rate") } returns BigDecimal("10.00")
            every { rs.getBigDecimal("current_doctor_rate") } returns BigDecimal("50.00")
            listOf(mapper.mapRow(rs, 0))
        }

        val view = service.list(doctorActor()).single()

        assertEquals(BigDecimal("700.00"), view.currentPrice)
        assertEquals("before service", view.currentServiceDescription)
        assertEquals(listOf("before-a", "before-b"), view.currentServiceTags)
        assertEquals(BigDecimal("20.00"), view.currentConsultationFee)
        assertEquals(BigDecimal("1000.00"), view.currentMedicalListPrice)
        assertEquals(BigDecimal("10.00"), view.currentPlatformRate)
        assertEquals(BigDecimal("50.00"), view.currentDoctorRate)
    }

    @Test
    fun `legal representative cannot force profile approval`() {
        assertThrows(AccessDeniedException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "force", true)
        }
    }

    @Test
    fun `profile update rejects zero effective project price`() {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM institution_projects") },
                any<RowMapper<Any>>(),
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(doctorId = "doctor-1", institutionProjectId = "ip-1", medicalListPrice = BigDecimal("900.00"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(doctorActor(), DoctorProjectChangeRequest(
                institutionProjectId = "ip-1", requestType = "PROFILE_UPDATE", serviceDescription = "service",
                priceSuggestion = BigDecimal.ZERO, notes = "notes", serviceTags = listOf("tag"), scheduleNote = "schedule",
                coverImage = "cover", images = listOf("image"), consultationFee = BigDecimal("30.00"),
                commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00"), medicalListPrice = BigDecimal.ZERO
            ))
        }

        assertEquals("MEDICAL_LIST_PRICE_NOT_POSITIVE", error.message)
    }

    @Test
    fun `profile update rejects fractional-cent medical list price before persistence`() {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM institution_projects") },
                any<RowMapper<Any>>(),
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(doctorId = "doctor-1", institutionProjectId = "ip-1", medicalListPrice = BigDecimal("900.00"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(doctorActor(), DoctorProjectChangeRequest(
                institutionProjectId = "ip-1", requestType = "PROFILE_UPDATE", serviceDescription = "service",
                priceSuggestion = BigDecimal("1000.005"), notes = "notes", serviceTags = listOf("tag"), scheduleNote = "schedule",
                coverImage = "cover", images = listOf("image"), consultationFee = BigDecimal("30.00"),
                commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00"), medicalListPrice = BigDecimal("1000.005")
            ))
        }

        assertEquals("金额须在范围内且最多两位小数", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `profile approval rejects drift before writing either effective row`() {
        stubReviewQueries(requestType = "PROFILE_UPDATE")
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject(updatedAt = LocalDateTime.of(2026, 8, 11, 10, 0))

        assertThrows(DoctorProjectChangeConflictException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
    }

    @Test
    fun `admin force applies exact doctor profile and records force audit`() {
        stubReviewQueries(requestType = "PROFILE_UPDATE", targetMedicalListPrice = BigDecimal("880.00"))
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns DoctorInstitutionProjectConfigEntity(id="config-1", doctorId="doctor-1", institutionProjectId="ip-1")
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        service.review(adminActor(), "request-1", "APPROVED", "override drift", true)

        val project = slot<DoctorProjectEntity>()
        verify(exactly = 1) { doctorProjectRepository.save(capture(project)) }
        assertEquals(BigDecimal("880.00"), project.captured.price)
        verify(exactly = 1) { configRepository.save(match { it.doctorId == "doctor-1" && it.consultationFee == BigDecimal("30.00") && it.medicalListPrice == BigDecimal("880.00") }) }
        verify(exactly = 1) { jdbcTemplate.update(match<String> { it.contains("force_processed = ?") }, *anyVararg()) }
    }

    @Test
    fun `approval rejects historic mismatched project and compatibility prices before effective writes`() {
        stubReviewQueries(
            requestType = "PROFILE_UPDATE",
            targetPriceSuggestion = BigDecimal("3999.00"),
            targetMedicalListPrice = BigDecimal("4299.00")
        )
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(id = "config-1", doctorId = "doctor-1", institutionProjectId = "ip-1")
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.review(adminActor(), "request-1", "APPROVED", "historic validation", true)
        }

        assertEquals("医生项目价格与兼容价格必须一致", error.message)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `approval rejects historic fractional-cent profile price before saving either effective row`() {
        stubReviewQueries(
            requestType = "PROFILE_UPDATE",
            targetPriceSuggestion = BigDecimal("1000.005"),
            targetMedicalListPrice = BigDecimal("1000.005")
        )
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(id = "config-1", doctorId = "doctor-1", institutionProjectId = "ip-1")
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.review(adminActor(), "request-1", "APPROVED", "migration validation", true)
        }

        assertEquals("金额须在范围内且最多两位小数", error.message)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `approval rejects historic out-of-range profile price before saving either effective row`() {
        stubReviewQueries(
            requestType = "PROFILE_UPDATE",
            targetPriceSuggestion = BigDecimal("100000000.00"),
            targetMedicalListPrice = BigDecimal("100000000.00")
        )
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(id = "config-1", doctorId = "doctor-1", institutionProjectId = "ip-1")
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { configRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.review(adminActor(), "request-1", "APPROVED", "migration validation", true)
        }

        assertEquals("金额须在范围内且最多两位小数", error.message)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { configRepository.save(any()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `join submission rejects legacy profile fields outside minimal contract`() {
        stubJoinSubmission()

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(
                doctorActor(),
                DoctorProjectChangeRequest(
                    institutionProjectId = "ip-1",
                    requestType = "JOIN",
                    serviceDescription = "service",
                    priceSuggestion = BigDecimal("880.00"),
                    notes = "notes",
                    serviceTags = listOf("legacy-tag")
                )
            )
        }

        assertEquals("加入机构项目仅允许提交服务内容、价格建议和说明", error.message)
    }

    @Test
    fun `join submission rejects zero price through shared travel pricing`() {
        stubJoinSubmission()

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.submit(
                doctorActor(),
                DoctorProjectChangeRequest(
                    institutionProjectId = "ip-1",
                    requestType = "JOIN",
                    serviceDescription = "service",
                    priceSuggestion = BigDecimal.ZERO,
                    notes = "notes"
                )
            )
        }

        assertEquals("MEDICAL_LIST_PRICE_NOT_POSITIVE", error.message)
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `join approval rejects historic zero price before creating binding`() {
        stubReviewQueries(targetPriceSuggestion = BigDecimal.ZERO)
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        assertEquals("MEDICAL_LIST_PRICE_NOT_POSITIVE", error.message)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
    }

    @Test
    fun `join approval stores doctor price and submitted service content`() {
        stubReviewQueries()
        every { doctorProjectRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every { doctorProjectRepository.save(any()) } answers { firstArg() }
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.review(legalActor(), "request-1", "APPROVED", "", false)

        val binding = slot<DoctorProjectEntity>()
        verify { doctorProjectRepository.save(capture(binding)) }
        verify { relationshipService.requireActiveRelationshipForUpdate("doctor-1", "institution-1") }
        assertEquals(BigDecimal("880.00"), binding.captured.price)
        assertEquals("service", binding.captured.serviceDescription)
        assertEquals("", binding.captured.serviceTags)
        assertEquals("", binding.captured.scheduleNote)
        assertEquals("", binding.captured.coverImage)
        assertEquals("", binding.captured.images)
        assertEquals("APPROVED", result.status)
    }

    @Test
    fun `join review supports changes requested without changing doctor binding`() {
        stubReviewQueries(status = "CHANGES_REQUESTED")
        every { jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_project_change_requests") }, *anyVararg()) } returns 1

        val result = service.review(legalActor(), "request-1", "CHANGES_REQUESTED", "请修改价格", false)

        assertEquals("CHANGES_REQUESTED", result.status)
        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    @Test
    fun `join approval rejects doctor whose institution relationship was revoked`() {
        stubReviewQueries()
        every { relationshipService.requireActiveRelationshipForUpdate("doctor-1", "institution-1") } throws
            org.springframework.security.access.AccessDeniedException("医生与机构的有效执业关系已失效")

        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.review(legalActor(), "request-1", "APPROVED", "", false)
        }

        verify(exactly = 0) { doctorProjectRepository.save(any()) }
    }

    private fun stubV2TargetQuery(doctorActive: Boolean) {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM doctor_projects dp") },
                any<RowMapper<Any>>(),
                "doctor-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(v2StateResultSet(doctorActive = doctorActive), 0))
        }
    }

    private fun stubV2SubmitState(
        doctorId: String = "doctor-1",
        currentName: String? = "Local Project",
        currentTags: String? = null,
        platformCategory: String = "Platform Category",
        doctorProject: DoctorProjectEntity? = doctorProject(doctorId = doctorId),
        pendingCount: Long = 0,
        revisionVersion: Long = 7,
        lockedVersion: Long = revisionVersion,
        lockedConfigUpdatedAt: LocalDateTime = LocalDateTime.of(2026, 8, 10, 10, 0),
        revisionPlatformCategory: String = platformCategory
    ): String {
        val configId = if (doctorId == "doctor-1") "config-1" else "config-$doctorId"
        every {
            jdbcTemplate.query(
                match<String> {
                    it.contains("SELECT ip.institution_id, ip.project_id") && !it.contains("FOR UPDATE")
                },
                any<RowMapper<Any>>(),
                doctorId,
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
                every { getString("institution_name") } returns "Institution"
                every { getString("doctor_name") } returns if (doctorId == "doctor-1") "Doctor" else "Doctor $doctorId"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { institutionProjectRepository.findForUpdate("ip-1") } returns InstitutionProjectEntity(
            id = "ip-1",
            institutionId = "institution-1",
            projectId = "project-1",
            name = currentName,
            category = null,
            description = null,
            tags = currentTags,
            slogan = null,
            detailContent = null,
            coverImage = null,
            images = null,
            salesCount = 8,
            version = lockedVersion
        )
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                "project-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(platformResultSet(category = platformCategory), 0))
        }
        every { doctorProjectRepository.findForUpdate(doctorId, "ip-1") } returns doctorProject
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM doctor_projects") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                doctorId,
                "ip-1"
            )
        } answers {
            if (doctorProject == null) emptyList() else {
                val mapper = secondArg<RowMapper<Any>>()
                val rs = mockk<ResultSet>(relaxed = true) {
                    every { getTimestamp("updated_at") } returns Timestamp.valueOf(doctorProject.updatedAt)
                }
                listOf(mapper.mapRow(rs, 0))
            }
        }
        every {
            configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(doctorId, "ip-1")
        } returns DoctorInstitutionProjectConfigEntity(
            id = configId,
            doctorId = doctorId,
            institutionProjectId = "ip-1",
            medicalListPrice = BigDecimal("100.00"),
            updatedAt = lockedConfigUpdatedAt
        )
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM doctor_institution_project_configs") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                doctorId,
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet>(relaxed = true) {
                every { getString("id") } returns configId
                every { getTimestamp("updated_at") } returns Timestamp.valueOf(lockedConfigUpdatedAt)
                every { getTimestamp("deleted_at") } returns null
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every {
            jdbcTemplate.queryForObject(
                match<String> { it.contains("status = 'PENDING'") },
                Long::class.java,
                doctorId,
                "ip-1"
            )
        } returns pendingCount

        val platformHash = DoctorProjectSnapshotCodec(objectMapper).platformInheritanceHash(
            PlatformInheritanceSource(
                platformProjectId = "project-1",
                name = "Platform Project",
                category = revisionPlatformCategory.ifBlank { "Platform Category" },
                description = "Platform Description",
                tags = "platform-tag",
                slogan = "Platform Slogan",
                detailContent = "Platform Detail",
                coverImage = "platform-cover",
                images = "platform-image"
            )
        )
        return DoctorProjectSnapshotCodec(objectMapper).baseRevision(
            DoctorProjectRevisionSource(
                institutionProjectId = "ip-1",
                institutionId = "institution-1",
                platformProjectId = "project-1",
                institutionProjectVersion = revisionVersion,
                platformInheritanceHash = platformHash,
                doctorProjectUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0)).toInstant(),
                configId = configId,
                configUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0)).toInstant(),
                pricingPolicyRevision = "travel-ground-service-rate:0.400000"
            )
        )
    }

    private fun v2Request(
        baseRevision: String,
        name: String? = "Local Project",
        category: String? = null,
        description: String? = null,
        tags: List<String>? = null,
        slogan: String? = null,
        detailContent: String? = null,
        price: BigDecimal = BigDecimal("100.00"),
        salesCount: Int = 8,
        doctorActive: Boolean = true,
        coverImage: String? = null,
        images: List<String>? = null
    ) = DoctorProjectChangeV2Request(
        requestType = "PROFILE_UPDATE",
        institutionProjectId = "ip-1",
        baseRevision = baseRevision,
        name = name,
        category = category,
        description = description,
        tags = tags,
        slogan = slogan,
        detailContent = detailContent,
        price = price,
        salesCount = salesCount,
        doctorActive = doctorActive,
        coverImage = coverImage,
        images = images,
        notes = "notes"
    )

    private fun platformResultSet(category: String = "Platform Category"): ResultSet = mockk(relaxed = true) {
        every { getString("id") } returns "project-1"
        every { getString("name") } returns "Platform Project"
        every { getString("category") } returns category
        every { getString("description") } returns "Platform Description"
        every { getString("tags") } returns "platform-tag"
        every { getString("slogan") } returns "Platform Slogan"
        every { getString("detail_content") } returns "Platform Detail"
        every { getString("cover_image") } returns "platform-cover"
        every { getString("images") } returns "platform-image"
    }

    private fun v2StateResultSet(doctorActive: Boolean = true): ResultSet = mockk(relaxed = true) {
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_name") } returns "Institution"
        every { getString("platform_project_id") } returns "project-1"
        every { getString("platform_project_name") } returns "Platform Project"
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("doctor_name") } returns "Doctor"
        every { getString("ip_name") } returns "Local Project"
        every { getString("ip_category") } returns null
        every { getString("ip_description") } returns null
        every { getString("ip_tags") } returns null
        every { getString("ip_slogan") } returns null
        every { getString("ip_detail_content") } returns null
        every { getString("ip_cover_image") } returns null
        every { getString("ip_images") } returns null
        every { getInt("ip_sales_count") } returns 8
        every { getLong("ip_version") } returns 7
        every { getString("platform_category") } returns "Platform Category"
        every { getString("platform_description") } returns "Platform Description"
        every { getString("platform_tags") } returns "platform-tag"
        every { getString("platform_slogan") } returns "Platform Slogan"
        every { getString("platform_detail_content") } returns "Platform Detail"
        every { getString("platform_cover_image") } returns "platform-cover"
        every { getString("platform_images") } returns "platform-image"
        every { getBigDecimal("doctor_price") } returns BigDecimal("100.00")
        every { getBoolean("doctor_active") } returns doctorActive
        every { getTimestamp("doctor_project_updated_at") } returns
            Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        every { getString("config_id") } returns "config-1"
        every { getTimestamp("config_updated_at") } returns
            Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
    }

    private fun captureV2Ledger(values: Array<*>): CapturedV2Ledger {
        val currentSnapshot = values[9] as String
        return CapturedV2Ledger(
            id = values[0] as String,
            doctorId = values[1] as String,
            institutionId = values[2] as String,
            institutionProjectId = values[3] as String,
            platformProjectId = DoctorProjectSnapshotCodec(objectMapper).decode(currentSnapshot).association.platformProjectId,
            baseInstitutionProjectVersion = values[4] as Long,
            basePlatformInheritanceHash = values[5] as String,
            pricingPolicyRevision = values[6] as String,
            travelGroundServiceFee = values[7] as BigDecimal,
            sharedChanged = values[8] as Boolean,
            currentProjectSnapshot = currentSnapshot,
            proposedProjectSnapshot = values[10] as String,
            baseDoctorProjectUpdatedAt = values[11] as Timestamp,
            baseConfigId = values[12] as String?,
            baseConfigUpdatedAt = values[13] as Timestamp?,
            currentDoctorPrice = values[14] as BigDecimal,
            proposedDoctorPrice = values[15] as BigDecimal,
            platformRate = values[16] as BigDecimal,
            currentDoctorActive = values[17] as Boolean,
            proposedDoctorActive = values[18] as Boolean,
            notes = values[19] as String,
            submittedBy = values[20] as String,
            submittedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 1, 2, 3))
        )
    }

    private fun v2ListResultSet(
        id: String = "request-v2",
        currentSnapshot: String = DoctorProjectSnapshotCodec(objectMapper).encode(validV2Snapshot()),
        proposedSnapshot: String = DoctorProjectSnapshotCodec(objectMapper).encode(validV2Snapshot()),
        latestVersion: Long = 7,
        currentPlatformRate: BigDecimal? = BigDecimal("40.00"),
        travelGroundServiceFee: BigDecimal? = BigDecimal("44.00"),
        ledger: CapturedV2Ledger? = null,
        liveState: V2LiveState? = null
    ): ResultSet = mockk(relaxed = true) {
        val now = Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 1, 2, 3))
        val live = liveState ?: V2LiveState(
            institutionProjectVersion = latestVersion,
            doctorPrice = BigDecimal("100.00"),
            doctorActive = true,
            doctorUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0)),
            configId = "config-1",
            configUpdatedAt = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        )
        every { getString("id") } returns (ledger?.id ?: id)
        every { getInt("payload_version") } returns 2
        every { getString("doctor_id") } returns (ledger?.doctorId ?: "doctor-1")
        every { getString("doctor_name") } returns "Doctor"
        every { getString("institution_id") } returns (ledger?.institutionId ?: "institution-1")
        every { getString("institution_name") } returns "Institution"
        every { getString("institution_project_id") } returns (ledger?.institutionProjectId ?: "ip-1")
        every { getString("institution_project_name") } returns "Local Project"
        every { getString("platform_project_id") } returns (ledger?.platformProjectId ?: "project-1")
        every { getString("platform_project_name") } returns "Platform Project"
        every { getString("request_type") } returns "PROFILE_UPDATE"
        every { getLong("base_institution_project_version") } returns (ledger?.baseInstitutionProjectVersion ?: 7L)
        every { getString("base_platform_inheritance_hash") } returns (
            ledger?.basePlatformInheritanceHash ?: validV2Snapshot().source.platformInheritanceHash
        )
        every { getString("pricing_policy_revision") } returns (
            ledger?.pricingPolicyRevision ?: "travel-ground-service-rate:0.400000"
        )
        every { getString("current_project_snapshot") } returns (ledger?.currentProjectSnapshot ?: currentSnapshot)
        every { getString("proposed_project_snapshot") } returns (ledger?.proposedProjectSnapshot ?: proposedSnapshot)
        every { getBoolean("shared_changed") } returns (ledger?.sharedChanged ?: true)
        every { getBigDecimal("current_price") } returns (ledger?.currentDoctorPrice ?: BigDecimal("100.00"))
        every { getBigDecimal("medical_list_price") } returns (ledger?.proposedDoctorPrice ?: BigDecimal("110.00"))
        every { getBoolean("current_doctor_is_active") } returns (ledger?.currentDoctorActive ?: true)
        every { getBoolean("proposed_doctor_is_active") } returns (ledger?.proposedDoctorActive ?: true)
        every { getBigDecimal("current_platform_rate") } returns (ledger?.platformRate ?: currentPlatformRate)
        every { getBigDecimal("proposed_travel_ground_service_fee") } returns (
            ledger?.travelGroundServiceFee ?: travelGroundServiceFee
        )
        every { getString("status") } returns "PENDING"
        every { getString("notes") } returns (ledger?.notes ?: "notes")
        every { getBoolean("force_processed") } returns false
        every { getString("submitted_by") } returns (ledger?.submittedBy ?: "doctor-1")
        every { getTimestamp("submitted_at") } returns (ledger?.submittedAt ?: now)
        every { getString("reviewed_by") } returns null
        every { getString("reviewer_name") } returns null
        every { getString("review_note") } returns null
        every { getTimestamp("reviewed_at") } returns null
        every { getTimestamp("updated_at") } returns now
        every { getTimestamp("base_doctor_project_updated_at") } returns (
            ledger?.baseDoctorProjectUpdatedAt ?: Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        )
        every { getString("base_config_id") } returns if (ledger != null) ledger.baseConfigId else "config-1"
        every { getTimestamp("base_config_updated_at") } returns if (ledger != null) {
            ledger.baseConfigUpdatedAt
        } else {
            Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        }
        every { getString("latest_ip_name") } returns "Local Project"
        every { getString("latest_ip_category") } returns null
        every { getString("latest_ip_description") } returns null
        every { getString("latest_ip_tags") } returns null
        every { getString("latest_ip_slogan") } returns null
        every { getString("latest_ip_detail_content") } returns null
        every { getString("latest_ip_cover_image") } returns null
        every { getString("latest_ip_images") } returns null
        every { getInt("latest_ip_sales_count") } returns 8
        every { getLong("latest_ip_version") } returns live.institutionProjectVersion
        every { getString("latest_platform_category") } returns "Platform Category"
        every { getString("latest_platform_description") } returns "Platform Description"
        every { getString("latest_platform_tags") } returns "platform-tag"
        every { getString("latest_platform_slogan") } returns "Platform Slogan"
        every { getString("latest_platform_detail_content") } returns "Platform Detail"
        every { getString("latest_platform_cover_image") } returns "platform-cover"
        every { getString("latest_platform_images") } returns "platform-image"
        every { getBigDecimal("latest_doctor_price") } returns live.doctorPrice
        every { getBoolean("latest_doctor_active") } returns live.doctorActive
        every { getTimestamp("latest_doctor_project_updated_at") } returns live.doctorUpdatedAt
        every { getString("latest_config_id") } returns live.configId
        every { getTimestamp("latest_config_updated_at") } returns live.configUpdatedAt
    }

    private fun validV2Snapshot(version: Long = 7): InstitutionProjectSnapshotV2 {
        val hash = DoctorProjectSnapshotCodec(objectMapper).platformInheritanceHash(
            PlatformInheritanceSource(
                platformProjectId = "project-1",
                name = "Platform Project",
                category = "Platform Category",
                description = "Platform Description",
                tags = "platform-tag",
                slogan = "Platform Slogan",
                detailContent = "Platform Detail",
                coverImage = "platform-cover",
                images = "platform-image"
            )
        )
        return InstitutionProjectSnapshotV2(
            schemaVersion = 2,
            association = ProjectAssociationSnapshot("ip-1", "institution-1", "project-1"),
            rawOverrides = ProjectRawOverridesSnapshot("Local Project", null, null, null, null, null, null, null),
            effective = ProjectEffectiveSnapshot(
                "Local Project", "Platform Category", "Platform Description", listOf("platform-tag"),
                "Platform Slogan", "Platform Detail", 8, "platform-cover", listOf("platform-image")
            ),
            source = ProjectSnapshotSource(version, hash)
        )
    }

    private fun stubProfileSubmission() {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM institution_projects") },
                any<RowMapper<Any>>(),
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns doctorProject()
        every { configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-1", "ip-1") } returns
            DoctorInstitutionProjectConfigEntity(
                doctorId = "doctor-1",
                institutionProjectId = "ip-1",
                medicalListPrice = BigDecimal("900.00")
            )
    }

    private fun stubJoinSubmission() {
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM institution_projects") },
                any<RowMapper<Any>>(),
                "ip-1"
            )
        } answers {
            val mapper = secondArg<RowMapper<Any>>()
            val rs = mockk<ResultSet> {
                every { getString("institution_id") } returns "institution-1"
                every { getString("project_id") } returns "project-1"
            }
            listOf(mapper.mapRow(rs, 0))
        }
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "ip-1") } returns null
    }

    private fun profileRequest(
        priceSuggestion: BigDecimal,
        medicalListPrice: BigDecimal
    ) = DoctorProjectChangeRequest(
        institutionProjectId = "ip-1",
        requestType = "PROFILE_UPDATE",
        serviceDescription = "service",
        priceSuggestion = priceSuggestion,
        notes = "notes",
        serviceTags = listOf("tag"),
        scheduleNote = "schedule",
        coverImage = "cover",
        images = listOf("image"),
        consultationFee = BigDecimal("30.00"),
        commissionRate = BigDecimal("10.00"),
        institutionRate = BigDecimal("40.00"),
        medicalListPrice = medicalListPrice
    )

    private fun stubReviewQueries(
        status: String = "APPROVED",
        requestType: String = "JOIN",
        targetPriceSuggestion: BigDecimal = BigDecimal("880.00"),
        targetMedicalListPrice: BigDecimal = BigDecimal("1000.00"),
        payloadVersion: Int = 1,
        targetStatus: String = "PENDING"
    ) {
        every { institutionProjectRepository.findForUpdate("ip-1") } returns InstitutionProjectEntity(
            id = "ip-1",
            institutionId = "institution-1",
            projectId = "project-1",
            isActive = true
        )
        every {
            configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-1", "ip-1")
        } returns null
        every { configRepository.findForUpdate("doctor-1", "ip-1") } returns null
        every {
            jdbcTemplate.queryForObject(match<String> { it.contains("doctor_institutions") }, Long::class.java, *anyVararg())
        } returns 1L
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            listOf(mapper.mapRow(viewResultSet(status), 0))
        }
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val mapper = secondArg<RowMapper<Any>>()
            val rs = when {
                sql.contains("FROM projects") -> mockk<ResultSet>(relaxed = true) {
                    every { getString("id") } returns "project-1"
                    every { getString("name") } returns "Project"
                }
                sql.contains("FOR UPDATE") -> targetResultSet(
                    requestType, targetPriceSuggestion, targetMedicalListPrice, payloadVersion, targetStatus
                )
                else -> viewResultSet(status, payloadVersion)
            }
            listOf(mapper.mapRow(rs, 0))
        }
    }

    private fun targetResultSet(
        requestType: String = "JOIN",
        priceSuggestion: BigDecimal = BigDecimal("880.00"),
        medicalListPrice: BigDecimal = BigDecimal("1000.00"),
        payloadVersion: Int = 1,
        status: String = "PENDING"
    ): ResultSet = mockk(relaxed = true) {
        every { getString("id") } returns "request-1"
        every { getInt("payload_version") } returns payloadVersion
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("request_type") } returns requestType
        every { getString("service_description") } returns "service"
        every { getString("service_tags") } returns "tag"
        every { getString("schedule_note") } returns "schedule"
        every { getString("cover_image") } returns ""
        every { getString("images") } returns ""
        every { getBigDecimal("price_suggestion") } returns priceSuggestion
        every { getString("notes") } returns "doctor notes"
        every { getString("status") } returns status
        every { getString("submitted_by") } returns "doctor-1"
        every { getBigDecimal("consultation_fee") } returns BigDecimal("30.00")
        every { getBigDecimal("commission_rate") } returns BigDecimal("10.00")
        every { getBigDecimal("institution_rate") } returns BigDecimal("40.00")
        every { getBigDecimal("medical_list_price") } returns medicalListPrice
        every { getTimestamp("base_doctor_project_updated_at") } returns Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        every { getString("base_config_id") } returns "config-1"
        every { getTimestamp("base_config_updated_at") } returns Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
    }

    private fun viewResultSet(status: String, payloadVersion: Int = 1): ResultSet = mockk(relaxed = true) {
        val now = Timestamp.valueOf(LocalDateTime.of(2026, 8, 10, 10, 0))
        every { getString("id") } returns "request-1"
        every { getString("doctor_id") } returns "doctor-1"
        every { getString("doctor_name") } returns "Doctor"
        every { getString("institution_id") } returns "institution-1"
        every { getString("institution_name") } returns "Institution"
        every { getString("institution_project_id") } returns "ip-1"
        every { getString("platform_project_id") } returns "project-1"
        every { getInt("payload_version") } returns payloadVersion
        every { getString("project_name") } returns "Project"
        every { getString("request_type") } returns "JOIN"
        every { getString("service_description") } returns "service"
        every { getString("service_tags") } returns "tag"
        every { getString("schedule_note") } returns "schedule"
        every { getString("cover_image") } returns ""
        every { getString("images") } returns ""
        every { getBigDecimal("price_suggestion") } returns BigDecimal("880.00")
        every { getString("notes") } returns "doctor notes"
        every { getString("status") } returns status
        every { getString("submitted_by") } returns "doctor-1"
        every { getString("reviewed_by") } returns "legal-1"
        every { getString("reviewer_name") } returns "Legal"
        every { getString("review_note") } returns ""
        every { getTimestamp("submitted_at") } returns now
        every { getTimestamp("reviewed_at") } returns now
        every { getTimestamp("updated_at") } returns now
    }

    private fun legalActor() = ManagementActor(
        userId = "legal-1",
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = setOf("institution-1"),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun adminActor() = legalActor().copy(userId="admin-1", isAdmin=true, managedInstitutionIds=emptySet())

    private fun doctorProject(
        doctorId: String = "doctor-1",
        updatedAt: LocalDateTime = LocalDateTime.of(2026, 8, 10, 10, 0)
    ) = DoctorProjectEntity(
        doctorId=doctorId, projectId="project-1", institutionProjectId="ip-1", price=BigDecimal("100.00"), updatedAt=updatedAt
    )

    private fun doctorActor(doctorId: String = "doctor-1") = ManagementActor(
        userId = doctorId,
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = doctorId,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf("institution-1"),
        manageableDoctorIds = setOf(doctorId)
    )

    private data class CapturedV2Ledger(
        val id: String,
        val doctorId: String,
        val institutionId: String,
        val institutionProjectId: String,
        val platformProjectId: String,
        val baseInstitutionProjectVersion: Long,
        val basePlatformInheritanceHash: String,
        val pricingPolicyRevision: String,
        val travelGroundServiceFee: BigDecimal,
        val sharedChanged: Boolean,
        val currentProjectSnapshot: String,
        val proposedProjectSnapshot: String,
        val baseDoctorProjectUpdatedAt: Timestamp,
        val baseConfigId: String?,
        val baseConfigUpdatedAt: Timestamp?,
        val currentDoctorPrice: BigDecimal,
        val proposedDoctorPrice: BigDecimal,
        val platformRate: BigDecimal,
        val currentDoctorActive: Boolean,
        val proposedDoctorActive: Boolean,
        val notes: String,
        val submittedBy: String,
        val submittedAt: Timestamp
    )

    private data class V2LiveState(
        val institutionProjectVersion: Long,
        val doctorPrice: BigDecimal,
        val doctorActive: Boolean,
        val doctorUpdatedAt: Timestamp,
        val configId: String?,
        val configUpdatedAt: Timestamp?
    )
}
