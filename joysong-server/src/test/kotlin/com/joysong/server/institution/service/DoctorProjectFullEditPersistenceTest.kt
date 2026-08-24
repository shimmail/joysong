package com.joysong.server.institution.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.project.service.InstitutionProjectPayloadPolicy
import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
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
    @Autowired private lateinit var splitProperties: OrderSplitProperties
    @Autowired private lateinit var cacheManager: CacheManager

    @Test
    fun `v2 shared approval atomically writes shared private compatibility and audit state`() {
        seed()
        val submitted = submit("doctor-1", sharedName = "Updated Project", price = BigDecimal("125.00"), active = false)

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
        val audit = objectMapper.readTree(
            text("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${submitted.id}'")
        )
        assertEquals(0L, audit.path("beforeVersion").asLong())
        assertEquals(1L, audit.path("afterVersion").asLong())
        assertEquals(false, audit.path("force").asBoolean())
        assertTrue(audit.has("latestBefore"))
        assertTrue(audit.has("actualApplied"))
    }

    @Test
    fun `two shared approvals serialize so only one shared write succeeds`() {
        seed()
        val first = submit("doctor-1", sharedName = "Doctor One", price = BigDecimal("111.00"), active = true)
        val second = submit("doctor-2", sharedName = "Doctor Two", price = BigDecimal("222.00"), active = true)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { request ->
                pool.submit<Result<Unit>> {
                    start.await(10, TimeUnit.SECONDS)
                    runCatching {
                        service.reviewV2(
                            adminActor(),
                            request.id,
                            DoctorProjectReviewV2Command(ProjectChangeDecision.APPROVED, "", false, null)
                        )
                        Unit
                    }
                }
            }
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, results.count(Result<Unit>::isSuccess))
            val failure = results.single { it.isFailure }.exceptionOrNull()
            assertTrue(failure is ProjectChangeContractException)
            assertEquals(ProjectChangeErrorCode.APPROVAL_BASE_STALE, (failure as ProjectChangeContractException).errorCode)
            assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
            assertEquals(1L, long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE status='APPROVED'"))
            assertEquals(1L, long("SELECT COUNT(*) FROM doctor_project_change_requests WHERE status='PENDING'"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `two doctors pure private approvals ignore unrelated shared and platform drift without shared writes`() {
        seed()
        val first = submit("doctor-1", sharedName = null, price = BigDecimal("111.00"), active = false)
        val second = submit("doctor-2", sharedName = null, price = BigDecimal("222.00"), active = false)
        jdbc.update("UPDATE institution_projects SET version=version+1, name='unrelated shared drift' WHERE id='ip-1'")
        jdbc.update("UPDATE projects SET category='unrelated platform drift' WHERE id='project-1'")

        service.reviewV2(adminActor(), first.id, approve())
        service.reviewV2(adminActor(), second.id, approve())

        assertEquals(1L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
        assertEquals(BigDecimal("111.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("222.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
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
        jdbc.update("UPDATE doctor_institutions SET revoked_at=NOW() WHERE id='di-1'")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(adminActor(), relationshipRequest.id, approve())
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${relationshipRequest.id}'"))
        assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))

        seed()
        val bindingRequest = submit("doctor-1", sharedName = "Changed", price = BigDecimal("125.00"), active = false)
        jdbc.update("DELETE FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'")
        assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
            service.reviewV2(adminActor(), bindingRequest.id, approve())
        }
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${bindingRequest.id}'"))
        assertEquals(0L, long("SELECT version FROM institution_projects WHERE id='ip-1'"))
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
        val freshRevision = (service.listV2(adminActor()).single { it.id == noDrift.id } as VersionedDoctorProjectChangeViewV2).latestRevision
        val stale = assertThrows(ProjectChangeContractException::class.java) {
            service.reviewV2(adminActor(), noDrift.id, force("0".repeat(64)))
        }
        assertEquals(ProjectChangeErrorCode.FORCE_BASE_STALE, stale.errorCode)
        service.reviewV2(adminActor(), noDrift.id, force(requireNotNull(freshRevision)))
        assertEquals("APPROVED", text("SELECT status FROM doctor_project_change_requests WHERE id='${noDrift.id}'"))
        val forceAudit = objectMapper.readTree(
            text("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id='${noDrift.id}'")
        )
        assertEquals(true, forceAudit.path("force").asBoolean())
        assertTrue(forceAudit.path("driftedFields").map { it.asText() }.contains("institutionProjectVersion"))

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
        val previousRate = splitProperties.platformRate
        try {
            splitProperties.platformRate = BigDecimal("41.00")
            val pricingError = assertThrows(ProjectChangeContractException::class.java) {
                service.reviewV2(adminActor(), priced.id, approve())
            }
            assertEquals(ProjectChangeErrorCode.PRICING_POLICY_STALE, pricingError.errorCode)
        } finally {
            splitProperties.platformRate = previousRate
        }
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
        service.reviewV2(adminActor(), leave.id, approve())

        assertEquals(0L, long("SELECT COUNT(*) FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals("APPROVED", text("SELECT status FROM doctor_project_change_requests WHERE id='${leave.id}'"))
    }

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
