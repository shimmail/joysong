package com.joysong.server.institution.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.joysong.server.admin.controller.InstitutionProjectController
import com.joysong.server.admin.entity.dto.DoctorProjectBinding
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.config.TravelGroundServicePricingProperties
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.project.service.InstitutionProjectPayloadPolicy
import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.cache.CacheManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(properties = [
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.flyway.baseline-on-migrate=false",
    "spring.flyway.validate-on-migrate=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=never",
    "order.split.platform-rate=40.00",
    "order.split.institution-rate=40.00"
])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DoctorProjectChangeService::class,
    DoctorInstitutionRelationshipService::class,
    InstitutionRelationshipReviewAuthorityService::class,
    OrderSplitRatePolicy::class,
    TravelGroundServicePricing::class,
    InstitutionProjectPayloadPolicy::class,
    OrderSplitProperties::class,
    ProfileUpdatePersistenceTestConfig::class
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DoctorProjectFullEditPersistenceTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var service: DoctorProjectChangeService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var pricingProperties: TravelGroundServicePricingProperties
    @Autowired private lateinit var cacheManager: CacheManager
    @Autowired private lateinit var institutionProjectRepository: InstitutionProjectRepository
    @Autowired private lateinit var doctorProjectRepository: DoctorProjectRepository
    @Autowired private lateinit var doctorRepository: DoctorRepository
    @Autowired private lateinit var doctorInstitutionRepository: DoctorInstitutionRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var institutionRepository: InstitutionRepository
    @Autowired private lateinit var configRepository: DoctorInstitutionProjectConfigRepository
    @Autowired private lateinit var orderRepository: OrderRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var travelGroundServicePricing: TravelGroundServicePricing

    @Test
    fun `direct put commits shared doctor config and cache state then stale retry writes nothing`() {
        seed()
        jdbc.update("UPDATE doctor_projects SET is_active=0 WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'")
        val pending = submit("doctor-1", sharedName = null, price = BigDecimal("111.00"), active = false)
        putCacheSentinels("direct-success")

        val updated = inTransaction {
            directController().update(
                adminAuthentication(),
                "ip-1",
                directUpdatePayload(
                    baseVersion = 0,
                    name = "Direct Updated",
                    doctorBindings = listOf(
                        DoctorProjectBinding("doctor-2", price = BigDecimal("200.00")),
                        DoctorProjectBinding("doctor-1", price = BigDecimal("125.00"))
                    )
                )
            ).data!!
        }

        assertEquals(1L, updated.version)
        assertEquals("Direct Updated", text("SELECT name FROM institution_projects WHERE id='ip-1'"))
        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(BigDecimal("125.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(0L, long("SELECT is_active FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("125.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-1'"))
        assertEquals(BigDecimal("200.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${pending.id}'"))
        assertCachesCleared("direct-success")

        val before = directState(pending.id)
        putCacheSentinels("direct-stale")
        val stale = assertThrows(ProjectChangeContractException::class.java) {
            inTransaction {
                directController().update(
                    adminAuthentication(),
                    "ip-1",
                    directUpdatePayload(
                        baseVersion = 0,
                        name = "Must Not Apply",
                        doctorBindings = listOf(
                            DoctorProjectBinding("doctor-1", price = BigDecimal("999.00")),
                            DoctorProjectBinding("doctor-2", price = BigDecimal("999.00"))
                        )
                    )
                )
            }
        }
        assertEquals(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE, stale.errorCode)
        assertEquals(before, directState(pending.id))
        assertCachesPresent("direct-stale")
    }

    @Test
    fun `two direct administrators with one base produce one coherent winner and one typed stale loser`() {
        seed()
        val payloads = listOf(
            directUpdatePayload(
                0,
                "Direct One",
                listOf(
                    DoctorProjectBinding("doctor-2", price = BigDecimal("210.00")),
                    DoctorProjectBinding("doctor-1", price = BigDecimal("110.00"))
                )
            ),
            directUpdatePayload(
                0,
                "Direct Two",
                listOf(
                    DoctorProjectBinding("doctor-1", price = BigDecimal("120.00")),
                    DoctorProjectBinding("doctor-2", price = BigDecimal("220.00"))
                )
            )
        )

        val results = runConcurrentTransactions(payloads.map { payload ->
            {
                requireNotNull(directController().update(adminAuthentication(), "ip-1", payload).data!!.name)
            }
        })

        assertEquals(1, results.count { it.isSuccess })
        assertConflictOnly(
            results.single { it.isFailure },
            setOf(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE)
        )
        val winner = results.single { it.isSuccess }.getOrThrow()
        assertEquals(winner, text("SELECT name FROM institution_projects WHERE id='ip-1'"))
        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        val expectedPrices = if (winner == "Direct One") {
            mapOf("doctor-1" to BigDecimal("110.00"), "doctor-2" to BigDecimal("210.00"))
        } else {
            mapOf("doctor-1" to BigDecimal("120.00"), "doctor-2" to BigDecimal("220.00"))
        }
        expectedPrices.forEach { (doctorId, price) ->
            assertEquals(price, decimal("SELECT price FROM doctor_projects WHERE doctor_id='$doctorId' AND institution_project_id='ip-1'"))
            assertEquals(price, decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE doctor_id='$doctorId' AND institution_project_id='ip-1'"))
        }
    }

    @Test
    fun `direct administrator racing shared approval yields one complete shared private config winner`() {
        seed()
        val approval = submit("doctor-1", sharedName = "Approval Winner", price = BigDecimal("125.00"), active = false)
        val directPayload = directUpdatePayload(
            0,
            "Direct Winner",
            listOf(
                DoctorProjectBinding("doctor-2", price = BigDecimal("230.00")),
                DoctorProjectBinding("doctor-1", price = BigDecimal("130.00"))
            )
        )
        val approvalPrefixHeld = CountDownLatch(1)
        val releaseApproval = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val approvalFuture = pool.submit<Result<VersionedDoctorProjectChangeViewV2>> {
            runCatching {
                inTransaction {
                    lockReviewPrefix(approval.id, "doctor-1")
                    approvalPrefixHeld.countDown()
                    check(releaseApproval.await(10, TimeUnit.SECONDS)) { "等待释放审批事务超时" }
                    service.reviewV2(adminActor(), approval.id, approve()) as VersionedDoctorProjectChangeViewV2
                }
            }
        }
        var directFuture: java.util.concurrent.Future<Result<Unit>>? = null
        try {
            check(approvalPrefixHeld.await(10, TimeUnit.SECONDS)) { "审批事务未在 10 秒内持有前缀锁" }
            directFuture = pool.submit<Result<Unit>> {
                runCatching {
                    inTransaction {
                        directController().update(adminAuthentication(), "ip-1", directPayload)
                        Unit
                    }
                }
            }
            assertTrue(
                awaitRequestTableLockWaiter(),
                "未在 performance_schema.data_lock_waits 观察到直改事务等待 doctor_project_change_requests"
            )
            releaseApproval.countDown()

            val reviewed = approvalFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            val directResult = directFuture.get(30, TimeUnit.SECONDS)
            assertConflictOnly(directResult, setOf(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE))

            assertEquals("APPROVED", reviewed.requestStatus)
            assertEquals("Approval Winner", text("SELECT name FROM institution_projects WHERE id='ip-1'"))
            assertEquals(BigDecimal("125.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
            assertEquals(0L, long("SELECT is_active FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
            assertEquals(BigDecimal("10.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE id='config-1'"))
            assertEquals(BigDecimal("0.00"), decimal("SELECT commission_rate FROM doctor_institution_project_configs WHERE id='config-1'"))
            assertEquals(BigDecimal("40.00"), decimal("SELECT institution_rate FROM doctor_institution_project_configs WHERE id='config-1'"))
            assertEquals(BigDecimal("125.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-1'"))
            assertEquals("APPROVED", text("SELECT status FROM doctor_project_change_requests WHERE id='${approval.id}'"))
            assertEquals("admin-1", text("SELECT reviewed_by FROM doctor_project_change_requests WHERE id='${approval.id}'"))
            assertEquals(
                1L,
                long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE id='${approval.id}' AND reviewed_at IS NOT NULL")
            )
            val audit = objectMapper.readTree(
                text("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${approval.id}'")
            )
            assertEquals(0L, audit.path("beforeVersion").asLong())
            assertEquals(1L, audit.path("afterVersion").asLong())
            assertAuditState(
                audit.path("latestBefore"),
                requireNotNull(approval.currentProject),
                BigDecimal("100.00"),
                true,
                BigDecimal("40.00")
            )
            assertAuditState(
                audit.path("actualApplied"),
                requireNotNull(reviewed.latestProject),
                BigDecimal("125.00"),
                false,
                BigDecimal("50.00")
            )
            assertEquals(false, audit.path("force").asBoolean())
            assertEquals(emptyList<String>(), audit.path("driftedFields").map(JsonNode::asText))

            assertEquals(BigDecimal("200.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
            assertEquals(1L, long("SELECT is_active FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
            assertEquals(BigDecimal("20.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE id='config-2'"))
            assertEquals(BigDecimal("0.00"), decimal("SELECT commission_rate FROM doctor_institution_project_configs WHERE id='config-2'"))
            assertEquals(BigDecimal("40.00"), decimal("SELECT institution_rate FROM doctor_institution_project_configs WHERE id='config-2'"))
            assertEquals(BigDecimal("200.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-2'"))
        } finally {
            releaseApproval.countDown()
            directFuture?.cancel(true)
            approvalFuture.cancel(true)
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "direct-vs-approval 线程池未能关闭" }
        }

        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
    }

    @Test
    fun `outer failure after direct cache registration rolls back writes and retains all caches`() {
        seed()
        val before = directStateForProjectOnly()
        putCacheSentinels("direct-rollback")

        assertThrows(ForcedDirectRollback::class.java) {
            inTransaction {
                directController().update(
                    adminAuthentication(),
                    "ip-1",
                    directUpdatePayload(
                        0,
                        "Rollback Direct",
                        listOf(
                            DoctorProjectBinding("doctor-1", price = BigDecimal("333.00")),
                            DoctorProjectBinding("doctor-2", price = BigDecimal("444.00"))
                        )
                    )
                )
                throw ForcedDirectRollback()
            }
        }

        assertEquals(before, directStateForProjectOnly())
        assertCachesPresent("direct-rollback")
    }

    @Test
    fun `direct NOWAIT avoids request gap deadlock with inflight submit prefix`() {
        seed()
        val actor = doctorActor("doctor-1")
        val target = service.listProfileUpdateTargetsV2(actor).single()
        val raw = target.currentProject.rawOverrides
        val submitRequest = DoctorProjectChangeV2Request(
            requestType = "PROFILE_UPDATE",
            institutionProjectId = "ip-1",
            baseRevision = target.baseRevision,
            name = raw.name,
            category = raw.category,
            description = raw.description,
            tags = raw.tags,
            slogan = raw.slogan,
            detailContent = raw.detailContent,
            price = BigDecimal("111.00"),
            salesCount = target.currentProject.effective.salesCount,
            doctorActive = false,
            coverImage = raw.coverImage,
            images = raw.images,
            notes = "inflight submit"
        )
        val before = directStateForProjectOnly()
        putCacheSentinels("inflight-submit")
        val prefixHeld = CountDownLatch(1)
        val allowSubmit = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val submitFuture = pool.submit<Result<VersionedDoctorProjectChangeViewV2>> {
            runCatching {
                inTransaction {
                    lockRealSubmitPrefix("doctor-1")
                    prefixHeld.countDown()
                    check(allowSubmit.await(10, TimeUnit.SECONDS)) { "等待 direct NOWAIT 结束超时" }
                    service.submitV2(actor, submitRequest) as VersionedDoctorProjectChangeViewV2
                }
            }
        }
        var directFuture: java.util.concurrent.Future<Result<Unit>>? = null
        try {
            check(prefixHeld.await(10, TimeUnit.SECONDS)) { "submit 未持有真实锁前缀" }
            directFuture = pool.submit<Result<Unit>> {
                runCatching {
                    inTransaction {
                        directController().update(
                            adminAuthentication(),
                            "ip-1",
                            directUpdatePayload(
                                0,
                                "Must Not Apply",
                                listOf(
                                    DoctorProjectBinding("doctor-1", price = BigDecimal("999.00")),
                                    DoctorProjectBinding("doctor-2", price = BigDecimal("999.00"))
                                )
                            )
                        )
                        Unit
                    }
                }
            }
            val directResult = directFuture.get(30, TimeUnit.SECONDS)
            assertConflictOnly(directResult, setOf(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE))
            allowSubmit.countDown()
            val submitted = submitFuture.get(30, TimeUnit.SECONDS).getOrThrow()

            assertEquals(1L, long("SELECT COUNT(*) FROM doctor_project_change_requests"))
            assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${submitted.id}'"))
            assertEquals(before, directStateForProjectOnly())
            assertCachesPresent("inflight-submit")
        } finally {
            allowSubmit.countDown()
            directFuture?.cancel(true)
            submitFuture.cancel(true)
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "inflight submit 线程池未关闭" }
        }
    }

    @Test
    fun `v2 shared approval atomically writes shared private compatibility and audit state`() {
        seed()
        val submitted = submit("doctor-1", sharedName = "Updated Project", price = BigDecimal("125.00"), active = false)
        val committedCache = requireNotNull(cacheManager.getCache("projects"))
        committedCache.put("successful-approval", "stale")

        val reviewed = service.reviewV2(
            legalActor(),
            submitted.id,
            DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
        ) as VersionedDoctorProjectChangeViewV2

        assertEquals("APPROVED", reviewed.requestStatus)
        assertEquals("Updated Project", text("SELECT name FROM institution_projects WHERE id='ip-1'"))
        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(BigDecimal("125.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(0L, long("SELECT is_active FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("125.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("200.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
        assertEquals(null, committedCache.get("successful-approval"))
        val audit = objectMapper.readTree(
            text("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${submitted.id}'")
        )
        assertEquals(
            setOf("beforeVersion", "afterVersion", "latestBefore", "actualApplied", "force", "driftedFields"),
            audit.fieldNames().asSequence().toSet()
        )
        assertEquals(0L, audit.path("beforeVersion").asLong())
        assertEquals(1L, audit.path("afterVersion").asLong())
        assertAuditState(
            audit.path("latestBefore"),
            requireNotNull(submitted.currentProject),
            BigDecimal("100.00"),
            true,
            BigDecimal("40.00")
        )
        assertAuditState(
            audit.path("actualApplied"),
            requireNotNull(reviewed.latestProject),
            BigDecimal("125.00"),
            false,
            BigDecimal("50.00")
        )
        assertEquals(false, audit.path("force").asBoolean())
        assertEquals(emptyList<String>(), audit.path("driftedFields").map(JsonNode::asText))
    }

    @Test
    fun `force token detects a doctor value change even when its six digit timestamp is preserved`() {
        seed()
        val submitted = submit("doctor-1", sharedName = null, price = BigDecimal("125.00"), active = false)
        val oldRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == submitted.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        val unchangedTimestamp = jdbc.queryForObject(
            "SELECT updated_at FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'",
            Timestamp::class.java
        )
        jdbc.update(
            "UPDATE doctor_projects SET price=111.00, updated_at=? WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'",
            unchangedTimestamp
        )
        val refreshedRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == submitted.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        val unchangedConfigTimestamp = jdbc.queryForObject(
            "SELECT updated_at FROM doctor_institution_project_configs WHERE id='config-1'",
            Timestamp::class.java
        )
        jdbc.update(
            "UPDATE doctor_institution_project_configs SET commission_rate=1.00, updated_at=? WHERE id='config-1'",
            unchangedConfigTimestamp
        )
        val configRefreshedRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == submitted.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )

        assertNotEquals(oldRevision, refreshedRevision)
        assertNotEquals(refreshedRevision, configRefreshedRevision)
        val stale = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(adminActor(), submitted.id, force(refreshedRevision))
        }
        assertEquals(ProjectChangeErrorCode.FORCE_BASE_STALE, stale.errorCode)
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${submitted.id}'"))
    }

    @Test
    fun `two shared approvals serialize so only one shared write succeeds`() {
        seed()
        val first = submit("doctor-1", sharedName = "Doctor One", price = BigDecimal("111.00"), active = true)
        val second = submit("doctor-2", sharedName = "Doctor Two", price = BigDecimal("222.00"), active = true)
        val prefixReady = CountDownLatch(2)
        val results = runConcurrentTransactions(
            listOf(
                { reviewAfterLockedPrefix(first.id, "doctor-1", prefixReady); Unit },
                { reviewAfterLockedPrefix(second.id, "doctor-2", prefixReady); Unit }
            )
        )

        assertEquals(1, results.count { it.isSuccess })
        assertConflictOnly(results.single { it.isFailure }, setOf(ProjectChangeErrorCode.APPROVAL_BASE_STALE))
        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE status='APPROVED'"))
        assertEquals(1L, long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE status='PENDING'"))
    }

    @Test
    fun `two doctors pure private approvals ignore unrelated shared and platform drift without shared writes`() {
        seed()
        val first = submit("doctor-1", sharedName = null, price = BigDecimal("111.00"), active = false)
        val second = submit("doctor-2", sharedName = null, price = BigDecimal("222.00"), active = false)
        jdbc.update("UPDATE institution_projects SET version=version+1, name='unrelated shared drift' WHERE id='ip-1'")
        jdbc.update("UPDATE projects SET category='unrelated platform drift' WHERE id='project-1'")

        val prefixReady = CountDownLatch(2)
        val results = runConcurrentTransactions(
            listOf(
                { reviewAfterLockedPrefix(first.id, "doctor-1", prefixReady); Unit },
                { reviewAfterLockedPrefix(second.id, "doctor-2", prefixReady); Unit }
            )
        )

        assertTrue(results.all { it.isSuccess })
        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(BigDecimal("111.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("222.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("111.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-1'"))
        assertEquals(BigDecimal("222.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-2'"))
        assertEquals(BigDecimal("10.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE id='config-1'"))
        assertEquals(BigDecimal("20.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE id='config-2'"))
        assertEquals(0L, long("SELECT COUNT(*) FROM doctor_projects WHERE institution_project_id='ip-1' AND is_active=1"))
        assertEquals(2L, long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE status='APPROVED'"))
    }

    @Test
    fun `reject and changes requested remain terminal after every business baseline drifts`() {
        listOf(ProjectChangeDecision.REJECTED, ProjectChangeDecision.CHANGES_REQUESTED).forEach { decision ->
            seed()
            val submitted = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
            jdbc.update("UPDATE institution_projects SET version=version+1, name='drift' WHERE id='ip-1'")
            jdbc.update("UPDATE projects SET category='drifted-platform' WHERE id='project-1'")
            jdbc.update("UPDATE doctor_projects SET price=999, updated_at=NOW() WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'")
            jdbc.update("UPDATE doctor_institution_project_configs SET updated_at=DATE_ADD(NOW(), INTERVAL 1 SECOND) WHERE id='config-1'")
            jdbc.update("UPDATE doctor_institutions SET revoked_at=NOW() WHERE id='di-1'")

            service.reviewV2(
                legalActor(),
                submitted.id,
                DoctorProjectReviewV2Command(decision, "terminal reason", false, null)
            )

            assertEquals(decision.name, text("SELECT status FROM doctor_project_change_requests WHERE id='${submitted.id}'"))
            assertEquals(BigDecimal("999.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        }
    }

    @Test
    fun `revoked reviewer authority and lost doctor relationship both deny approval with zero writes`() {
        seed()
        val authorityRequest = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        jdbc.update("UPDATE institution_memberships SET revoked_at=NOW() WHERE id='membership-legal'")

        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(legalActor(), authorityRequest.id, approve())
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${authorityRequest.id}'"))
        assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(BigDecimal("100.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))

        seed()
        val relationshipRequest = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val relationshipRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == relationshipRequest.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update("UPDATE doctor_institutions SET revoked_at=NOW() WHERE id='di-1'")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(adminActor(), relationshipRequest.id, force(relationshipRevision))
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${relationshipRequest.id}'"))
        assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))

        seed()
        val bindingRequest = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val bindingRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == bindingRequest.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update("DELETE FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(adminActor(), bindingRequest.id, force(bindingRevision))
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${bindingRequest.id}'"))
        assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))

        seed()
        val associationRequest = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val associationRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == associationRequest.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update(
            "INSERT INTO projects (id,name,category,description,tags,slogan,cover_image,images) VALUES ('project-2','Other','Category','Description','[\"tag\"]','Slogan','cover','[\"image\"]')"
        )
        jdbc.update("UPDATE institution_projects SET project_id='project-2' WHERE id='ip-1'")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(adminActor(), associationRequest.id, force(associationRevision))
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${associationRequest.id}'"))
        assertEquals(BigDecimal("100.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
    }

    @Test
    fun `force requires real allowed drift fresh revision and cannot bypass inheritance or pricing drift`() {
        seed()
        val noDrift = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val noDriftRevision = (service.listV2(adminActor()).single { it.id == noDrift.id } as VersionedDoctorProjectChangeViewV2).latestRevision
        val notApplicable = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(adminActor(), noDrift.id, force(requireNotNull(noDriftRevision)))
        }
        assertEquals(ProjectChangeErrorCode.FORCE_NOT_APPLICABLE, notApplicable.errorCode)

        jdbc.update("UPDATE institution_projects SET version=version+1, name='latest' WHERE id='ip-1'")
        val latestBeforeForce = service.listV2(adminActor()).single { it.id == noDrift.id }
            as VersionedDoctorProjectChangeViewV2
        val freshRevision = latestBeforeForce.latestRevision
        val stale = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(adminActor(), noDrift.id, force("0".repeat(64)))
        }
        assertEquals(ProjectChangeErrorCode.FORCE_BASE_STALE, stale.errorCode)
        val forcedReview = service.reviewV2(adminActor(), noDrift.id, force(requireNotNull(freshRevision)))
            as VersionedDoctorProjectChangeViewV2
        assertEquals("APPROVED", text("SELECT status FROM doctor_project_change_requests WHERE id='${noDrift.id}'"))
        val forceAudit = objectMapper.readTree(
            text("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${noDrift.id}'")
        )
        assertEquals(
            setOf("beforeVersion", "afterVersion", "latestBefore", "actualApplied", "force", "driftedFields"),
            forceAudit.fieldNames().asSequence().toSet()
        )
        assertEquals(1L, forceAudit.path("beforeVersion").asLong())
        assertEquals(2L, forceAudit.path("afterVersion").asLong())
        assertAuditState(
            forceAudit.path("latestBefore"),
            requireNotNull(latestBeforeForce.latestProject),
            BigDecimal("100.00"),
            true,
            BigDecimal("40.00")
        )
        assertAuditState(
            forceAudit.path("actualApplied"),
            requireNotNull(forcedReview.latestProject),
            BigDecimal("125.00"),
            false,
            BigDecimal("50.00")
        )
        assertEquals(true, forceAudit.path("force").asBoolean())
        assertEquals(listOf("institutionProjectVersion"), forceAudit.path("driftedFields").map(JsonNode::asText))

        seed()
        val inherited = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        jdbc.update("UPDATE projects SET category='new inheritance' WHERE id='project-1'")
        val inheritedRevision = (service.listV2(adminActor()).single { it.id == inherited.id } as VersionedDoctorProjectChangeViewV2).latestRevision
        val inheritanceError = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(adminActor(), inherited.id, force(requireNotNull(inheritedRevision)))
        }
        assertEquals(ProjectChangeErrorCode.INHERITANCE_SOURCE_STALE, inheritanceError.errorCode)

        seed()
        val priced = submit("doctor-1", sharedName = null, price = BigDecimal("125.00"), active = false)
        val previousRate = pricingProperties.serviceFeeRate
        try {
            pricingProperties.serviceFeeRate = BigDecimal("41.00")
            val pricingRevision = requireNotNull(
                (service.listV2(adminActor()).single { it.id == priced.id } as VersionedDoctorProjectChangeViewV2)
                    .latestRevision
            )
            val pricingError = assertThrows(ProjectChangeContractException::class.java) {
                service.reviewV2(adminActor(), priced.id, force(pricingRevision))
            }
            assertEquals(ProjectChangeErrorCode.PRICING_POLICY_STALE, pricingError.errorCode)
        } finally {
            pricingProperties.serviceFeeRate = previousRate
        }
    }

    @Test
    fun `force cannot bypass invalid snapshots invalid fields or stored fee mismatch`() {
        seed()
        val invalidSnapshot = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val invalidSnapshotRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == invalidSnapshot.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update(
            "UPDATE doctor_project_change_requests SET proposed_project_snapshot=JSON_OBJECT() WHERE id=?",
            invalidSnapshot.id
        )
        assertEquals(
            ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID,
            assertThrows(ProjectChangeContractException::class.java) {
                service.reviewV2(adminActor(), invalidSnapshot.id, force(invalidSnapshotRevision))
            }.errorCode
        )
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${invalidSnapshot.id}'"))

        seed()
        val invalidField = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        val invalidFieldRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == invalidField.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update(
            "UPDATE doctor_project_change_requests SET proposed_project_snapshot=JSON_SET(proposed_project_snapshot, '\$.rawOverrides.name', '   ') WHERE id=?",
            invalidField.id
        )
        assertEquals(
            ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID,
            assertThrows(ProjectChangeContractException::class.java) {
                service.reviewV2(adminActor(), invalidField.id, force(invalidFieldRevision))
            }.errorCode
        )
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${invalidField.id}'"))

        seed()
        val feeMismatch = submit("doctor-1", sharedName = null, price = BigDecimal("125.00"), active = false)
        val feeRevision = requireNotNull(
            (service.listV2(adminActor()).single { it.id == feeMismatch.id } as VersionedDoctorProjectChangeViewV2)
                .latestRevision
        )
        jdbc.update(
            "UPDATE doctor_project_change_requests SET proposed_travel_ground_service_fee=999.00 WHERE id=?",
            feeMismatch.id
        )
        assertEquals(
            ProjectChangeErrorCode.PRICING_POLICY_STALE,
            assertThrows(ProjectChangeContractException::class.java) {
                service.reviewV2(adminActor(), feeMismatch.id, force(feeRevision))
            }.errorCode
        )
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${feeMismatch.id}'"))
    }

    @Test
    fun `failure at every approval write stage rolls back all prior writes and cache eviction`() {
        val stages = listOf(
            Triple("shared", "institution_projects", "name <> 'Rollback Shared'"),
            Triple("doctor", "doctor_projects", "price <> 333.00"),
            Triple("config", "doctor_institution_project_configs", "medical_list_price <> 333.00"),
            Triple("request", "doctor_project_change_requests", "status <> 'APPROVED'")
        )
        stages.forEach { (stage, table, condition) ->
            seed()
            val submitted = submit("doctor-1", sharedName = "Rollback Shared", price = BigDecimal("333.00"), active = false)
            val cache = requireNotNull(cacheManager.getCache("projects"))
            cache.put("rollback-$stage", "present")
            jdbc.update("ALTER TABLE $table ADD CONSTRAINT fail_$stage CHECK ($condition)")
            try {
                assertThrows(Exception::class.java) {
                    service.reviewV2(adminActor(), submitted.id, approve())
                }
            } finally {
                jdbc.update("ALTER TABLE $table DROP CHECK fail_$stage")
            }

            assertEquals(null, jdbc.queryForObject("SELECT name FROM institution_projects WHERE id='ip-1'", String::class.java))
            assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
            assertEquals(BigDecimal("100.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
            assertEquals(1L, long("SELECT is_active FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
            assertEquals(BigDecimal("100.00"), decimal("SELECT medical_list_price FROM doctor_institution_project_configs WHERE id='config-1'"))
            assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${submitted.id}'"))
            assertEquals(null, jdbc.queryForObject("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${submitted.id}'", String::class.java))
            assertEquals("present", cache.get("rollback-$stage", String::class.java))
            cache.clear()
        }
    }

    @Test
    fun `v2 withdraw supports own v2 row while v1 leave stays mutually exclusive and reviewable through v2`() {
        seed()
        val edit = submit("doctor-1", sharedName = null, price = BigDecimal("125.00"), active = false)
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.withdrawV2(adminActor(), edit.id)
        }
        assertThrows(DoctorProjectChangeConflictException::class.java) {
            service.submit(
                doctorActor("doctor-1"),
                DoctorProjectChangeRequest(institutionProjectId = "ip-1", requestType = "LEAVE")
            )
        }

        val withdrawn = service.withdrawV2(doctorActor("doctor-1"), edit.id) as VersionedDoctorProjectChangeViewV2
        assertEquals("WITHDRAWN", withdrawn.requestStatus)
        val leave = service.submit(
            doctorActor("doctor-1"),
            DoctorProjectChangeRequest(institutionProjectId = "ip-1", requestType = "LEAVE")
        )
        assertEquals("LEAVE", leave.requestType)
        val withdrawnLeave = service.withdrawV2(doctorActor("doctor-1"), leave.id)
            as LegacyDoctorProjectChangeViewV2
        assertEquals("WITHDRAWN", withdrawnLeave.status)
        val finalLeave = service.submit(
            doctorActor("doctor-1"),
            DoctorProjectChangeRequest(institutionProjectId = "ip-1", requestType = "LEAVE")
        )
        jdbc.update("UPDATE doctor_institutions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE id='di-1'")
        service.reviewV2(adminActor(), finalLeave.id, approve())

        assertEquals(0L, long("SELECT COUNT(*) FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals("APPROVED", text("SELECT status FROM doctor_project_change_requests WHERE id='${finalLeave.id}'"))
    }

    @Test
    fun `legacy submit revalidates a revoked relationship instead of trusting the actor snapshot`() {
        seed()
        jdbc.update("UPDATE doctor_institutions SET revoked_at=CURRENT_TIMESTAMP(6) WHERE id='di-1'")

        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.submit(
                doctorActor("doctor-1"),
                DoctorProjectChangeRequest(institutionProjectId = "ip-1", requestType = "LEAVE")
            )
        }

        assertEquals(0L, long("SELECT COUNT(*) FROM doctor_project_change_requests"))
    }

    private fun directController() = InstitutionProjectController(
        institutionProjectRepository = institutionProjectRepository,
        doctorProjectRepository = doctorProjectRepository,
        doctorRepository = doctorRepository,
        projectRepository = projectRepository,
        institutionRepository = institutionRepository,
        configRepository = configRepository,
        orderRepository = orderRepository,
        detailResolver = InstitutionProjectDetailResolver(),
        doctorInstitutionService = DoctorInstitutionService(doctorInstitutionRepository, institutionRepository),
        managementAccessService = ManagementAccessService(jdbc),
        jdbcTemplate = jdbc,
        travelGroundServicePricing = travelGroundServicePricing,
        objectMapper = objectMapper,
        cacheManager = cacheManager
    )

    private fun adminAuthentication() = UsernamePasswordAuthenticationToken(
        "admin-1",
        "unused",
        listOf(SimpleGrantedAuthority("ROLE_ADMIN"))
    )

    private fun directUpdatePayload(
        baseVersion: Long,
        name: String,
        doctorBindings: List<DoctorProjectBinding>
    ): ObjectNode = objectMapper.createObjectNode().apply {
        put("baseVersion", baseVersion)
        put("name", name)
        putNull("category")
        putNull("description")
        putNull("rating")
        putNull("reviewCount")
        putNull("tags")
        putNull("slogan")
        putNull("detailContent")
        put("price", BigDecimal("3500.00"))
        putNull("originalPrice")
        put("currency", "USD")
        putNull("coverImage")
        putNull("images")
        put("salesCount", 3)
        put("isActive", true)
        set<JsonNode>("doctorBindings", objectMapper.valueToTree(doctorBindings))
    }

    private fun <T : Any> inTransaction(block: () -> T): T =
        requireNotNull(TransactionTemplate(transactionManager).execute { block() })

    private fun putCacheSentinels(key: String) {
        listOf("discover", "home", "projects").forEach { cacheName ->
            requireNotNull(cacheManager.getCache(cacheName)).put(key, "present")
        }
    }

    private fun assertCachesCleared(key: String) {
        listOf("discover", "home", "projects").forEach { cacheName ->
            assertEquals(null, requireNotNull(cacheManager.getCache(cacheName)).get(key))
        }
    }

    private fun assertCachesPresent(key: String) {
        listOf("discover", "home", "projects").forEach { cacheName ->
            assertEquals("present", requireNotNull(cacheManager.getCache(cacheName)).get(key, String::class.java))
        }
    }

    private fun directState(requestId: String) = DirectState(
        project = jdbc.queryForMap(
            "SELECT name,category,description,rating,review_count,tags,slogan,detail_content,price,original_price,currency,cover_image,images,sales_count,is_active,version,updated_at FROM institution_projects WHERE id='ip-1'"
        ),
        doctors = jdbc.queryForList(
            "SELECT doctor_id,project_id,institution_project_id,price,service_description,service_tags,schedule_note,cover_image,images,is_active,created_at,updated_at FROM doctor_projects WHERE institution_project_id='ip-1' ORDER BY doctor_id"
        ),
        configs = jdbc.queryForList(
            "SELECT id,doctor_id,institution_project_id,consultation_fee,commission_rate,institution_rate,medical_list_price,updated_at,deleted_at FROM doctor_institution_project_configs WHERE institution_project_id='ip-1' ORDER BY id"
        ),
        request = jdbc.queryForMap(
            "SELECT status,reviewed_by,reviewed_at,review_note,approval_audit_snapshot,updated_at FROM doctor_project_change_requests WHERE id=?",
            requestId
        )
    )

    private data class DirectState(
        val project: Map<String, Any>,
        val doctors: List<Map<String, Any>>,
        val configs: List<Map<String, Any>>,
        val request: Map<String, Any>
    )

    private fun directStateForProjectOnly() = ProjectOnlyState(
        project = jdbc.queryForMap(
            "SELECT name,category,description,rating,review_count,tags,slogan,detail_content,price,original_price,currency,cover_image,images,sales_count,is_active,version,updated_at FROM institution_projects WHERE id='ip-1'"
        ),
        doctors = jdbc.queryForList(
            "SELECT doctor_id,project_id,institution_project_id,price,service_description,service_tags,schedule_note,cover_image,images,is_active,created_at,updated_at FROM doctor_projects WHERE institution_project_id='ip-1' ORDER BY doctor_id"
        ),
        configs = jdbc.queryForList(
            "SELECT id,doctor_id,institution_project_id,consultation_fee,commission_rate,institution_rate,medical_list_price,updated_at,deleted_at FROM doctor_institution_project_configs WHERE institution_project_id='ip-1' ORDER BY id"
        )
    )

    private data class ProjectOnlyState(
        val project: Map<String, Any>,
        val doctors: List<Map<String, Any>>,
        val configs: List<Map<String, Any>>
    )

    private fun <T : Any> runConcurrentTransactions(tasks: List<() -> T>): List<Result<T>> {
        val enteredTransactions = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(tasks.size)
        val futures = tasks.map { task ->
            pool.submit<Result<T>> {
                runCatching {
                    inTransaction {
                        enteredTransactions.countDown()
                        check(start.await(10, TimeUnit.SECONDS)) { "事务内起跑屏障超时" }
                        task()
                    }
                }
            }
        }
        try {
            check(enteredTransactions.await(10, TimeUnit.SECONDS)) { "事务未在 10 秒内进入屏障" }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "并发测试线程池未能关闭" }
        }
    }

    private fun reviewAfterLockedPrefix(
        requestId: String,
        doctorId: String,
        prefixReady: CountDownLatch
    ) {
        lockReviewPrefix(requestId, doctorId)
        prefixReady.countDown()
        check(prefixReady.await(10, TimeUnit.SECONDS)) { "request relationship 前缀屏障超时" }
        service.reviewV2(adminActor(), requestId, approve())
    }

    private fun lockReviewPrefix(requestId: String, doctorId: String) {
        assertEquals(
            requestId,
            jdbc.queryForObject(
                "SELECT id FROM doctor_project_change_requests WHERE id=? AND status='PENDING' FOR UPDATE",
                String::class.java,
                requestId
            )
        )
        assertEquals(
            doctorId,
            jdbc.queryForObject(
                "SELECT doctor_id FROM doctor_institutions WHERE doctor_id=? AND institution_id='institution-1' AND status='APPROVED' AND revoked_at IS NULL AND deleted_at IS NULL FOR UPDATE",
                String::class.java,
                doctorId
            )
        )
    }

    private fun awaitRequestTableLockWaiter(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiters = DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blocking
                      ON blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE()
                      AND requested.OBJECT_NAME = 'doctor_project_change_requests'
                      AND blocking.OBJECT_SCHEMA = DATABASE()
                      AND blocking.OBJECT_NAME = 'doctor_project_change_requests'
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next()) { "performance_schema waiter 查询未返回结果" }
                        result.getInt(1)
                    }
                }
            }
            if (waiters >= 1) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun lockRealSubmitPrefix(doctorId: String) {
        assertEquals(
            doctorId,
            jdbc.queryForObject(
                "SELECT doctor_id FROM doctor_institutions WHERE doctor_id=? AND institution_id='institution-1' AND status='APPROVED' AND revoked_at IS NULL AND deleted_at IS NULL FOR UPDATE",
                String::class.java,
                doctorId
            )
        )
        assertEquals(
            "ip-1",
            jdbc.queryForObject("SELECT id FROM institution_projects WHERE id='ip-1' FOR UPDATE", String::class.java)
        )
        assertEquals(
            "project-1",
            jdbc.queryForObject("SELECT id FROM projects WHERE id='project-1' FOR UPDATE", String::class.java)
        )
        assertEquals(
            doctorId,
            jdbc.queryForObject(
                "SELECT doctor_id FROM doctor_projects WHERE doctor_id=? AND institution_project_id='ip-1' FOR UPDATE",
                String::class.java,
                doctorId
            )
        )
        assertEquals(
            "config-1",
            jdbc.queryForObject(
                "SELECT id FROM doctor_institution_project_configs WHERE doctor_id=? AND institution_project_id='ip-1' FOR UPDATE",
                String::class.java,
                doctorId
            )
        )
    }

    private fun <T> assertConflictOnly(result: Result<T>, expectedCodes: Set<ProjectChangeErrorCode>) {
        val error = result.exceptionOrNull()
        assertTrue(
            error is ProjectChangeContractException && error.errorCode in expectedCodes,
            "并发失败必须是指定的业务冲突，不能是死锁、锁超时或基础设施异常；实际=${error?.javaClass?.name}:${error?.message}"
        )
    }

    private class ForcedDirectRollback : RuntimeException("forced rollback after cache registration")

    private fun submit(
        doctorId: String,
        sharedName: String?,
        price: BigDecimal,
        active: Boolean
    ): VersionedDoctorProjectChangeViewV2 {
        val actor = doctorActor(doctorId)
        val target = service.listProfileUpdateTargetsV2(actor).single()
        val raw = target.currentProject.rawOverrides
        return service.submitV2(
            actor,
            DoctorProjectChangeV2Request(
                requestType = "PROFILE_UPDATE",
                institutionProjectId = "ip-1",
                baseRevision = target.baseRevision,
                name = sharedName,
                category = raw.category,
                description = raw.description,
                tags = raw.tags,
                slogan = raw.slogan,
                detailContent = raw.detailContent,
                price = price,
                salesCount = target.currentProject.effective.salesCount,
                doctorActive = active,
                coverImage = raw.coverImage,
                images = raw.images,
                notes = "test"
            )
        ) as VersionedDoctorProjectChangeViewV2
    }

    private fun seed() {
        jdbc.update("DELETE FROM doctor_project_change_requests")
        jdbc.update("DELETE FROM doctor_institution_project_configs")
        jdbc.update("DELETE FROM doctor_projects")
        jdbc.update("DELETE FROM doctor_institutions")
        jdbc.update("DELETE FROM institution_memberships")
        jdbc.update("DELETE FROM user_roles")
        jdbc.update("DELETE FROM institution_projects")
        jdbc.update("DELETE FROM projects")
        jdbc.update("DELETE FROM doctors")
        jdbc.update("DELETE FROM institutions")
        jdbc.update("DELETE FROM users")
        jdbc.update("INSERT INTO users (id,password_hash,nickname) VALUES ('doctor-1','x','D1'),('doctor-2','x','D2'),('legal-1','x','L'),('admin-1','x','A')")
        jdbc.update("INSERT INTO doctors (id,name,is_verified) VALUES ('doctor-1','D1',1),('doctor-2','D2',1)")
        jdbc.update("INSERT INTO institutions (id,name,is_verified) VALUES ('institution-1','Institution',1)")
        jdbc.update("INSERT INTO user_roles (user_id,role_code,status) VALUES ('legal-1','INSTITUTION_LEGAL_REPRESENTATIVE','ACTIVE')")
        jdbc.update("INSERT INTO institution_memberships (id,user_id,institution_id,member_role,status) VALUES ('membership-legal','legal-1','institution-1','INSTITUTION_LEGAL_REPRESENTATIVE','APPROVED')")
        jdbc.update("INSERT INTO projects (id,name,category,description,tags,slogan,cover_image,images) VALUES ('project-1','Platform Project','Category','Description','[\"tag\"]','Slogan','cover','[\"image\"]')")
        jdbc.update("INSERT INTO institution_projects (id,institution_id,project_id,is_active,version,sales_count) VALUES ('ip-1','institution-1','project-1',1,0,3)")
        jdbc.update("INSERT INTO doctor_institutions (id,doctor_id,institution_id,status) VALUES ('di-1','doctor-1','institution-1','APPROVED'),('di-2','doctor-2','institution-1','APPROVED')")
        jdbc.update("INSERT INTO doctor_projects (doctor_id,project_id,institution_project_id,price,service_description,service_tags,is_active) VALUES ('doctor-1','project-1','ip-1',100,'one','',1),('doctor-2','project-1','ip-1',200,'two','',1)")
        jdbc.update("INSERT INTO doctor_institution_project_configs (id,doctor_id,institution_project_id,consultation_fee,commission_rate,institution_rate,medical_list_price) VALUES ('config-1','doctor-1','ip-1',10,0,40,100),('config-2','doctor-2','ip-1',20,0,40,200)")
    }

    private fun doctorActor(id: String) = ManagementActor(id, false, setOf("DOCTOR"), id, emptySet(), setOf("institution-1"), setOf(id))
    private fun legalActor() = ManagementActor("legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, setOf("institution-1"), emptySet(), emptySet())
    private fun adminActor() = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
    private fun approve() = DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
    private fun force(revision: String) = DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "forced", true, revision)
    private fun text(sql: String): String = jdbc.queryForObject(sql, String::class.java)!!
    private fun long(sql: String): Long = jdbc.queryForObject(sql, Long::class.java)!!
    private fun decimal(sql: String): BigDecimal = jdbc.queryForObject(sql, BigDecimal::class.java)!!

    private fun assertAuditState(
        actual: JsonNode,
        expectedProject: InstitutionProjectSnapshotV2,
        expectedDoctorPrice: BigDecimal,
        expectedDoctorActive: Boolean,
        expectedFee: BigDecimal
    ) {
        assertEquals(
            setOf("project", "doctorPrice", "doctorActive", "travelGroundServiceFee"),
            actual.fieldNames().asSequence().toSet()
        )
        assertEquals(
            expectedProject,
            DoctorProjectSnapshotCodec(objectMapper).decode(actual.path("project").toString())
        )
        assertEquals(0, expectedDoctorPrice.compareTo(actual.path("doctorPrice").decimalValue()))
        assertEquals(expectedDoctorActive, actual.path("doctorActive").asBoolean())
        assertEquals(0, expectedFee.compareTo(actual.path("travelGroundServiceFee").decimalValue()))
    }

    companion object {
        @Container @ServiceConnection @JvmField
        val mysql = DoctorProjectFullEditMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class DoctorProjectFullEditMySqlContainer(imageName: String) : MySQLContainer<DoctorProjectFullEditMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
