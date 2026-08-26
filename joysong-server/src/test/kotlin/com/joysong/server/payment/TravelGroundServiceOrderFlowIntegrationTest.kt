package com.joysong.server.payment

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.DoctorProjectChangeService
import com.joysong.server.institution.service.DoctorProjectChangeV2Request
import com.joysong.server.institution.service.DoctorProjectChangeViewV2
import com.joysong.server.institution.service.DoctorProjectReviewV2Command
import com.joysong.server.institution.service.ProjectChangeDecision
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentNextAction
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.provider.ProviderRefundRequest
import com.joysong.server.payment.provider.ProviderRefundResult
import com.joysong.server.payment.provider.VerifiedProviderEvent
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.support.WorktreeTestDatabase
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP acceptance coverage for the travel-ground-service-only order contract.
 *
 * The gateway is deliberately test-scoped: every order, webhook, refund, lock,
 * and state change under test is handled by the production Spring services.
 */
@Tag("mysql-integration")
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.task.scheduling.enabled=false",
        "payment.reconciliation.enabled=false",
        "payment.stripe.legacy-enabled=false",
        "jwt.secret=0123456789abcdef0123456789abcdef",
        "google.client-id=travel-flow-test-google-client",
        "admin.bootstrap.phone=13800138000",
        "admin.bootstrap.password=travel-flow-admin-password",
        "ai-agent.provider=QWEN",
        "ai-agent.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
        "ai-agent.api-key=travel-flow-test-key",
        "ai-agent.model=travel-flow-test-model",
        "ai-agent.intent-model=travel-flow-test-intent-model",
        "order.split.platform-rate=40.00"
    ]
)
@AutoConfigureMockMvc
@Import(TravelGroundServiceOrderFlowIntegrationTest.TestPaymentGatewayConfiguration::class)
class TravelGroundServiceOrderFlowIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var institutionRepository: InstitutionRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var institutionProjectRepository: InstitutionProjectRepository

    @Autowired
    private lateinit var doctorRepository: DoctorRepository

    @Autowired
    private lateinit var doctorInstitutionRepository: DoctorInstitutionRepository

    @Autowired
    private lateinit var doctorProjectRepository: DoctorProjectRepository

    @Autowired
    private lateinit var priceConfigRepository: DoctorInstitutionProjectConfigRepository

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var orderService: OrderService

    @Autowired
    private lateinit var doctorProjectChangeService: DoctorProjectChangeService

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var refundRepository: RefundRepository

    @Autowired
    private lateinit var refundItemRepository: RefundItemRepository

    @Autowired
    private lateinit var testGateway: ScriptedAlipayPlusGateway

    private lateinit var fixture: TravelFlowFixture

    @BeforeEach
    fun seedMinimumTravelOrderFixture() {
        testGateway.reset()
        fixture = TravelFlowFixture.create()
        val fixtureUpdatedAt = LocalDateTime.now().let { current ->
            current.withNano(current.nano / 1_000 * 1_000)
        }

        userRepository.saveAll(
            listOf(
                UserEntity(
                    id = fixture.userId,
                    email = "${fixture.userId}@example.test",
                    passwordHash = "not-used-by-http-principal",
                    nickname = "Travel User"
                ),
                UserEntity(
                    id = fixture.adminId,
                    email = "${fixture.adminId}@example.test",
                    passwordHash = "not-used-by-http-principal",
                    nickname = "Travel Admin",
                    role = "ADMIN"
                ),
                UserEntity(
                    id = fixture.doctorId,
                    email = "${fixture.doctorId}@example.test",
                    passwordHash = "not-used-by-http-principal",
                    nickname = "Travel Doctor",
                    role = "DOCTOR"
                ),
                UserEntity(
                    id = fixture.consultantId,
                    email = "${fixture.consultantId}@example.test",
                    passwordHash = "not-used-by-http-principal",
                    nickname = "Travel Consultant",
                    avatar = "https://example.test/consultant.png"
                )
            )
        )
        institutionRepository.save(
            InstitutionEntity(
                id = fixture.institutionId,
                name = "Travel Flow Institution",
                isVerified = true
            )
        )
        projectRepository.save(
            ProjectEntity(
                id = fixture.projectId,
                name = "Travel Flow Project",
                category = "Travel treatment",
                description = "Travel flow project description",
                tags = "[\"travel\"]",
                slogan = "Travel flow slogan",
                coverImage = "https://example.test/project.png",
                images = "[\"https://example.test/project-detail.png\"]",
                referencePrice = BigDecimal("1000.00"),
                currency = "USD"
            )
        )
        institutionProjectRepository.save(
            InstitutionProjectEntity(
                id = fixture.institutionProjectId,
                institutionId = fixture.institutionId,
                projectId = fixture.projectId,
                name = "Travel Flow Institution Project",
                price = BigDecimal("1000.00"),
                currency = "USD",
                isActive = true
            )
        )
        doctorRepository.save(
            DoctorEntity(
                id = fixture.doctorId,
                name = "Travel Flow Doctor",
                institutionId = fixture.institutionId,
                institutionName = "Travel Flow Institution"
            )
        )
        doctorInstitutionRepository.save(
            DoctorInstitutionEntity(
                id = fixture.doctorInstitutionId,
                doctorId = fixture.doctorId,
                institutionId = fixture.institutionId,
                isPrimary = true,
                status = "APPROVED"
            )
        )
        doctorProjectRepository.save(
            DoctorProjectEntity(
                doctorId = fixture.doctorId,
                projectId = fixture.projectId,
                institutionProjectId = fixture.institutionProjectId,
                price = BigDecimal("1000.00"),
                updatedAt = fixtureUpdatedAt
            )
        )
        priceConfigRepository.saveAndFlush(
            DoctorInstitutionProjectConfigEntity(
                id = fixture.priceConfigId,
                doctorId = fixture.doctorId,
                institutionProjectId = fixture.institutionProjectId,
                consultationFee = BigDecimal.ZERO,
                commissionRate = BigDecimal.ZERO,
                institutionRate = BigDecimal("40.00"),
                medicalListPrice = BigDecimal("1000.00"),
                updatedAt = fixtureUpdatedAt
            )
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships (
                id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at
            ) VALUES (?, ?, ?, 'CONSULTANT', 'APPROVED', ?, CURRENT_TIMESTAMP)
            """.trimIndent(),
            fixture.membershipId,
            fixture.consultantId,
            fixture.institutionId,
            fixture.adminId
        )
    }

    @Test
    fun `travel order HTTP flow activates only after verified fee and retries its failed original-channel refund`() {
        val orderId = createTravelOrder()

        mockMvc.perform(
            post("/api/orders/$orderId/service-fee-payment-attempts")
                .header("Idempotency-Key", "service-fee-attempt-${fixture.token}")
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.paymentType").value("TRAVEL_GROUND_SERVICE_FEE"))
            .andExpect(jsonPath("$.data.provider").value("ALIPAY_PLUS"))
            .andExpect(jsonPath("$.data.status").value("REQUIRES_ACTION"))

        val payment = paymentRepository.findByOrderId(orderId).orElseThrow()
        assertEquals(40_000L, payment.amountMinor)
        val providerPaymentId = requireNotNull(payment.providerPaymentId)
        mockMvc.perform(
            post("/api/payment-webhooks/ALIPAY_PLUS")
                .contentType(MediaType.TEXT_PLAIN)
                .content("service-fee-succeeded:$providerPaymentId")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PROCESSED"))

        mockMvc.perform(
            get("/api/orders/$orderId")
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("SERVICE_ACTIVE"))
            .andExpect(jsonPath("$.data.serviceActivated").value(true))
            .andExpect(jsonPath("$.data.consultantId").value(fixture.consultantId))
            .andExpect(jsonPath("$.data.consultantDetailsVisible").value(true))
            .andExpect(jsonPath("$.data.serviceMessagingEnabled").value(true))

        mockMvc.perform(
            post("/api/orders/$orderId/confirm-completion")
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.serviceConversationReadable").value(true))
            .andExpect(jsonPath("$.data.serviceMessagingEnabled").value(false))
        assertNull(orderRepository.findById(orderId).orElseThrow().settlementAt)

        val rejectedRefundId = applyRefund(orderId, "first completed refund")
        assertEquals("REFUND_REVIEW", orderRepository.findById(orderId).orElseThrow().status)
        assertAdminCanSeeCompletedRefund(rejectedRefundId)
        mockMvc.perform(
            put("/api/admin/refunds/$rejectedRefundId/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"REJECTED","rejectReason":"manual review rejected"}""")
                .with(authentication(principal(fixture.adminId, "ADMIN")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("REJECTED"))
        assertEquals("COMPLETED", orderRepository.findById(orderId).orElseThrow().status)

        val retryableRefundId = applyRefund(orderId, "second completed refund")
        assertAdminCanSeeCompletedRefund(retryableRefundId)
        mockMvc.perform(
            put("/api/admin/refunds/$retryableRefundId/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"APPROVED"}""")
                .with(authentication(principal(fixture.adminId, "ADMIN")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("REFUND_PROCESSING"))

        val failedItem = refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(retryableRefundId).single()
        assertEquals("FAILED", failedItem.status)
        assertEquals("TEST_REFUND_INITIAL_FAILURE", failedItem.failureCode)
        assertNotNull(failedItem.providerRefundId)
        assertEquals("REFUND_PROCESSING", refundRepository.findById(retryableRefundId).orElseThrow().status)
        assertEquals("REFUND_PROCESSING", orderRepository.findById(orderId).orElseThrow().status)

        mockMvc.perform(
            post("/api/admin/refunds/$retryableRefundId/retry")
                .with(authentication(principal(fixture.adminId, "ADMIN")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("APPROVED"))

        val completedItem = refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(retryableRefundId).single()
        assertEquals("SUCCEEDED", completedItem.status)
        assertEquals("APPROVED", refundRepository.findById(retryableRefundId).orElseThrow().status)
        assertEquals("REFUNDED", orderRepository.findById(orderId).orElseThrow().status)
    }

    @Test
    fun `ordinary string principal cannot call an admin refund endpoint`() {
        mockMvc.perform(
            get("/api/admin/refunds")
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
    }

    @Test
    fun `public doctor project query enforces all availability dimensions and restores after reactivation`() {
        val suffix = fixture.token.take(8)
        val inactiveInstitutionProjectId = "matrix-ip-off-$suffix"
        val inactiveDoctorProjectId = "matrix-dp-off-$suffix"
        val inactiveRelationshipProjectId = "matrix-rel-off-$suffix"
        val inactiveRelationshipDoctorId = "matrix-doc-rel-$suffix"
        val inactiveInstitutionPlatformProjectId = "matrix-prj-ip-$suffix"
        val inactiveDoctorPlatformProjectId = "matrix-prj-dp-$suffix"
        val inactiveRelationshipPlatformProjectId = "matrix-prj-rel-$suffix"

        jdbcTemplate.update(
            """
            INSERT INTO projects (id, name, reference_price, currency) VALUES
                (?, 'Inactive institution platform project', 700, 'USD'),
                (?, 'Inactive doctor platform project', 800, 'USD'),
                (?, 'Inactive relationship platform project', 900, 'USD')
            """.trimIndent(),
            inactiveInstitutionPlatformProjectId,
            inactiveDoctorPlatformProjectId,
            inactiveRelationshipPlatformProjectId
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_projects (
                id, institution_id, project_id, name, price, currency, is_active
            ) VALUES
                (?, ?, ?, 'Inactive institution project', 700, 'USD', 0),
                (?, ?, ?, 'Inactive doctor project', 800, 'USD', 1),
                (?, ?, ?, 'Inactive relationship project', 900, 'USD', 1)
            """.trimIndent(),
            inactiveInstitutionProjectId, fixture.institutionId, inactiveInstitutionPlatformProjectId,
            inactiveDoctorProjectId, fixture.institutionId, inactiveDoctorPlatformProjectId,
            inactiveRelationshipProjectId, fixture.institutionId, inactiveRelationshipPlatformProjectId
        )
        jdbcTemplate.update(
            "INSERT INTO users (id, password_hash, nickname) VALUES (?, 'x', 'Inactive Relationship Doctor')",
            inactiveRelationshipDoctorId
        )
        jdbcTemplate.update(
            "INSERT INTO doctors (id, name, is_verified) VALUES (?, 'Inactive Relationship Doctor', 1)",
            inactiveRelationshipDoctorId
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institutions (
                id, doctor_id, institution_id, status, revoked_at
            ) VALUES (?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP)
            """.trimIndent(),
            "matrix-rel-$suffix", inactiveRelationshipDoctorId, fixture.institutionId
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_projects (
                doctor_id, project_id, institution_project_id, price, is_active
            ) VALUES
                (?, ?, ?, 700, 1),
                (?, ?, ?, 800, 0),
                (?, ?, ?, 900, 1)
            """.trimIndent(),
            fixture.doctorId, inactiveInstitutionPlatformProjectId, inactiveInstitutionProjectId,
            fixture.doctorId, inactiveDoctorPlatformProjectId, inactiveDoctorProjectId,
            inactiveRelationshipDoctorId, inactiveRelationshipPlatformProjectId, inactiveRelationshipProjectId
        )

        val candidateIds = listOf(
            fixture.institutionProjectId,
            inactiveInstitutionProjectId,
            inactiveDoctorProjectId,
            inactiveRelationshipProjectId
        )
        val publicBefore = doctorProjectRepository.findPublicByInstitutionProjectIds(candidateIds)
        val manageableBefore = doctorProjectRepository.findByDoctorId(fixture.doctorId)

        assertEquals(listOf(fixture.institutionProjectId), publicBefore.map { it.institutionProjectId })
        assertTrue(manageableBefore.any { it.institutionProjectId == inactiveDoctorProjectId && !it.isActive })

        jdbcTemplate.update(
            "UPDATE doctor_projects SET is_active=1 WHERE doctor_id=? AND institution_project_id=?",
            fixture.doctorId,
            inactiveDoctorProjectId
        )

        val publicAfter = doctorProjectRepository.findPublicByInstitutionProjectIds(candidateIds)
        val manageableAfter = doctorProjectRepository.findByDoctorId(fixture.doctorId)

        assertEquals(
            setOf(fixture.institutionProjectId, inactiveDoctorProjectId),
            publicAfter.map { it.institutionProjectId }.toSet()
        )
        assertEquals(
            manageableBefore.map { it.institutionProjectId }.toSet(),
            manageableAfter.map { it.institutionProjectId }.toSet()
        )
        assertTrue(manageableAfter.single { it.institutionProjectId == inactiveDoctorProjectId }.isActive)
    }

    @Test
    fun `deactivation approval wins before a new order without partial order writes`() {
        val requestId = submitDoctorDeactivation()
        val ordersBefore = countRows("orders")
        val statusLogsBefore = countRows("order_status_logs")
        val pool = Executors.newFixedThreadPool(3)
        val blockerHeld = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val blockerFuture = startRowLockBlocker(
            pool = pool,
            sql = "SELECT id FROM institution_projects WHERE id=? FOR UPDATE",
            parameters = listOf(fixture.institutionProjectId),
            held = blockerHeld,
            release = releaseBlocker
        )
        var approvalFuture: Future<Result<DoctorProjectChangeViewV2>>? = null
        var orderFuture: Future<Result<OrderResponse>>? = null
        try {
            assertBlockerHeld(blockerHeld, blockerFuture, "institution project blocker")
            approvalFuture = pool.submit<Result<DoctorProjectChangeViewV2>> {
                runCatching {
                    doctorProjectChangeService.reviewV2(adminActor(), requestId, approveChange())
                }
            }
            assertTrue(
                awaitTableLockWaiters("institution_projects", "institution_projects"),
                "approval must wait in MySQL on the blocked institution-project row"
            )

            orderFuture = pool.submit<Result<OrderResponse>> {
                runCatching { orderService.createOrder(fixture.userId, orderRequest()) }
            }
            assertTrue(
                awaitTableLockWaiters("doctor_institutions", "doctor_institutions"),
                "order must wait in MySQL on the relationship held by approval"
            )

            releaseBlocker.countDown()
            blockerFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            approvalFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            val orderResult = orderFuture.get(30, TimeUnit.SECONDS)
            val orderError = orderResult.exceptionOrNull()

            assertTrue(
                orderError is IllegalArgumentException,
                "order must end with the doctor-disabled business rejection; actual=${orderError?.javaClass?.name}:${orderError?.message}"
            )
            assertEquals("所选医生服务已停用，暂不可预约", orderError?.message)
            assertEquals(
                "APPROVED",
                jdbcTemplate.queryForObject(
                    "SELECT status FROM doctor_project_change_requests WHERE id=?",
                    String::class.java,
                    requestId
                )
            )
            assertTrue(
                !requireNotNull(
                    doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(
                        fixture.doctorId,
                        fixture.institutionProjectId
                    )
                ).isActive
            )
            assertEquals(ordersBefore, countRows("orders"))
            assertEquals(statusLogsBefore, countRows("order_status_logs"))
        } finally {
            releaseBlocker.countDown()
            closeWorkers(pool, blockerFuture, approvalFuture, orderFuture)
        }
    }

    @Test
    fun `new order wins before deactivation and keeps its immutable travel service snapshots`() {
        val requestId = submitDoctorDeactivation()
        val ordersBefore = countRows("orders")
        val statusLogsBefore = countRows("order_status_logs")
        val pool = Executors.newFixedThreadPool(3)
        val blockerHeld = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val blockerFuture = startRowLockBlocker(
            pool = pool,
            sql = """
                SELECT doctor_id
                FROM doctor_projects
                WHERE doctor_id=? AND institution_project_id=?
                FOR UPDATE
            """.trimIndent(),
            parameters = listOf(fixture.doctorId, fixture.institutionProjectId),
            held = blockerHeld,
            release = releaseBlocker
        )
        var orderFuture: Future<Result<OrderResponse>>? = null
        var approvalFuture: Future<Result<DoctorProjectChangeViewV2>>? = null
        try {
            assertBlockerHeld(blockerHeld, blockerFuture, "doctor project blocker")
            orderFuture = pool.submit<Result<OrderResponse>> {
                runCatching { orderService.createOrder(fixture.userId, orderRequest()) }
            }
            assertTrue(
                awaitTableLockWaiters("doctor_projects", "doctor_projects"),
                "order must wait in MySQL on the blocked doctor-project row"
            )

            approvalFuture = pool.submit<Result<DoctorProjectChangeViewV2>> {
                runCatching {
                    doctorProjectChangeService.reviewV2(adminActor(), requestId, approveChange())
                }
            }
            assertTrue(
                awaitTableLockWaiters("doctor_institutions", "doctor_institutions"),
                "deactivation approval must wait in MySQL on the relationship held by the order"
            )

            releaseBlocker.countDown()
            blockerFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            val created = orderFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            approvalFuture.get(30, TimeUnit.SECONDS).getOrThrow()
            val persisted = orderRepository.findById(created.id).orElseThrow()

            assertEquals("PENDING_SERVICE_FEE", persisted.status)
            assertEquals(fixture.doctorId, persisted.doctorId)
            assertEquals(fixture.institutionProjectId, persisted.institutionProjectId)
            assertEquals("USD", persisted.currency)
            assertEquals(100_000L, persisted.medicalListPriceMinor)
            assertEquals(4_000, persisted.platformServiceRateBps)
            assertEquals(40_000L, persisted.travelGroundServiceFeeMinor)
            assertEquals(40_000L, persisted.totalAmountMinor)
            assertEquals(BigDecimal("400.00"), persisted.price)
            assertEquals(
                0L,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refunds WHERE order_id=?",
                    Long::class.java,
                    persisted.id
                )
            )
            assertEquals(
                "APPROVED",
                jdbcTemplate.queryForObject(
                    "SELECT status FROM doctor_project_change_requests WHERE id=?",
                    String::class.java,
                    requestId
                )
            )
            assertTrue(
                !requireNotNull(
                    doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(
                        fixture.doctorId,
                        fixture.institutionProjectId
                    )
                ).isActive
            )
            assertEquals(ordersBefore + 1, countRows("orders"))
            assertEquals(statusLogsBefore + 1, countRows("order_status_logs"))
        } finally {
            releaseBlocker.countDown()
            closeWorkers(pool, blockerFuture, orderFuture, approvalFuture)
        }
    }

    private fun submitDoctorDeactivation(): String {
        val actor = doctorActor()
        val target = doctorProjectChangeService.listProfileUpdateTargetsV2(actor)
            .single { it.institutionProjectId == fixture.institutionProjectId }
        val raw = target.currentProject.rawOverrides
        return doctorProjectChangeService.submitV2(
            actor,
            DoctorProjectChangeV2Request(
                requestType = "PROFILE_UPDATE",
                institutionProjectId = fixture.institutionProjectId,
                baseRevision = target.baseRevision,
                name = raw.name,
                category = raw.category,
                description = raw.description,
                tags = raw.tags,
                slogan = raw.slogan,
                detailContent = raw.detailContent,
                price = target.currentDoctorPrice,
                salesCount = target.currentProject.effective.salesCount,
                doctorActive = false,
                coverImage = raw.coverImage,
                images = raw.images,
                notes = "deterministic Task 7 deactivation race"
            )
        ).id
    }

    private fun orderRequest() = CreateOrderRequest(
        projectId = fixture.projectId,
        institutionProjectId = fixture.institutionProjectId,
        doctorId = fixture.doctorId,
        consultantId = fixture.consultantId
    )

    private fun doctorActor() = ManagementActor(
        userId = fixture.doctorId,
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = fixture.doctorId,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = setOf(fixture.institutionId),
        manageableDoctorIds = setOf(fixture.doctorId)
    )

    private fun adminActor() = ManagementActor(
        userId = fixture.adminId,
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun approveChange() = DoctorProjectReviewV2Command(
        decision = ProjectChangeDecision.APPROVED,
        reviewNote = "Task 7 deterministic race",
        force = false,
        forceBaseRevision = null
    )

    private fun startRowLockBlocker(
        pool: ExecutorService,
        sql: String,
        parameters: List<String>,
        held: CountDownLatch,
        release: CountDownLatch
    ): Future<Result<Unit>> = pool.submit<Result<Unit>> {
        runCatching {
            DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { connection ->
                connection.autoCommit = false
                try {
                    connection.prepareStatement(sql).use { statement ->
                        parameters.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                        statement.executeQuery().use { result ->
                            check(result.next()) { "blocker row does not exist" }
                        }
                    }
                    held.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "row-lock blocker release timed out" }
                    connection.commit()
                } finally {
                    runCatching { connection.rollback() }
                }
            }
        }
    }

    private fun assertBlockerHeld(
        held: CountDownLatch,
        blocker: Future<Result<Unit>>,
        label: String
    ) {
        if (!held.await(10, TimeUnit.SECONDS)) {
            if (blocker.isDone) blocker.get(1, TimeUnit.SECONDS).getOrThrow()
            error("$label was not acquired within 10 seconds")
        }
    }

    private fun awaitTableLockWaiters(requestedTable: String, blockingTable: String): Boolean {
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
                      AND requested.OBJECT_NAME = ?
                      AND blocking.OBJECT_SCHEMA = DATABASE()
                      AND blocking.OBJECT_NAME = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, requestedTable)
                    statement.setString(2, blockingTable)
                    statement.executeQuery().use { result ->
                        check(result.next()) { "performance_schema waiter query returned no row" }
                        result.getInt(1)
                    }
                }
            }
            if (waiters >= 1) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun countRows(table: String): Long {
        require(table in setOf("orders", "order_status_logs"))
        return requireNotNull(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java))
    }

    private fun closeWorkers(pool: ExecutorService, vararg futures: Future<*>?) {
        futures.filterNotNull().forEach { it.cancel(true) }
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "race worker pool must terminate within 10 seconds")
    }

    private fun createTravelOrder(): String {
        val result = mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "projectId":"${fixture.projectId}",
                      "institutionProjectId":"${fixture.institutionProjectId}",
                      "doctorId":"${fixture.doctorId}",
                      "consultantId":"${fixture.consultantId}"
                    }
                    """.trimIndent()
                )
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("PENDING_SERVICE_FEE"))
            .andExpect(jsonPath("$.data.paymentFlow").value("TRAVEL_GROUND_SERVICE_ONLY"))
            .andExpect(jsonPath("$.data.serviceActivated").value(false))
            .andExpect(jsonPath("$.data.consultantId").value(nullValue()))
            .andExpect(jsonPath("$.data.consultantName").value(nullValue()))
            .andExpect(jsonPath("$.data.consultantDetailsVisible").value(false))
            .andReturn()
        val orderId = responseData(result, "id")
        assertTrue(orderId.isNotBlank())
        return orderId
    }

    private fun applyRefund(orderId: String, reason: String): String {
        val result = mockMvc.perform(
            post("/api/orders/$orderId/refund")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"$reason","reasonCode":"CUSTOMER_REQUEST"}""")
                .with(authentication(principal(fixture.userId, "USER")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andReturn()
        return responseData(result, "id")
    }

    private fun assertAdminCanSeeCompletedRefund(refundId: String) {
        val result = mockMvc.perform(
            get("/api/admin/refunds")
                .with(authentication(principal(fixture.adminId, "ADMIN")))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
        val records = objectMapper.readTree(result.response.contentAsString).path("data")
        assertTrue(
            records.any { record ->
                record.path("id").asText() == refundId &&
                    record.path("originalStatus").asText() == "COMPLETED"
            },
            "Admin refund list must expose the completed-order origin"
        )
    }

    private fun responseData(result: MvcResult, field: String): String =
        objectMapper.readTree(result.response.contentAsString)
            .path("data")
            .path(field)
            .asText()

    private fun principal(userId: String, role: String) = UsernamePasswordAuthenticationToken(
        userId,
        null,
        listOf(SimpleGrantedAuthority("ROLE_$role"))
    )

    @TestConfiguration(proxyBeanMethods = false)
    class TestPaymentGatewayConfiguration {
        @Bean
        fun scriptedAlipayPlusGateway(): ScriptedAlipayPlusGateway = ScriptedAlipayPlusGateway()
    }

    /** Test-only provider boundary; production does not contain an ALIPAY_PLUS fake. */
    class ScriptedAlipayPlusGateway : PaymentGateway {
        override val provider: PaymentProvider = PaymentProvider.ALIPAY_PLUS

        private val payments = ConcurrentHashMap<String, PaymentSnapshot>()
        private val refundRequests = AtomicInteger()

        fun reset() {
            payments.clear()
            refundRequests.set(0)
        }

        override fun createPayment(request: ProviderCreatePaymentRequest): ProviderPaymentResult {
            val providerPaymentId = "test-payment-${request.paymentId}"
            payments[providerPaymentId] = PaymentSnapshot(request.amountMinor, request.currency)
            return ProviderPaymentResult(
                status = PaymentStatus.REQUIRES_ACTION,
                providerPaymentId = providerPaymentId,
                amountMinor = request.amountMinor,
                currency = request.currency,
                nextAction = PaymentNextAction.Redirect("https://cashier.example.test/$providerPaymentId")
            )
        }

        override fun refund(request: ProviderRefundRequest): ProviderRefundResult {
            val providerRefundId = "test-refund-${request.refundItemId}"
            return if (refundRequests.incrementAndGet() == 1) {
                ProviderRefundResult(
                    status = PaymentStatus.FAILED,
                    providerRefundId = providerRefundId,
                    failureCode = "TEST_REFUND_INITIAL_FAILURE",
                    failureMessage = "The test provider rejected the first refund attempt"
                )
            } else {
                throw IllegalStateException("Existing provider refund must be queried instead of resent")
            }
        }

        override fun queryRefund(providerRefundId: String): ProviderRefundResult {
            require(providerRefundId.startsWith("test-refund-")) { "TEST_PROVIDER_REFUND_ID_INVALID" }
            return ProviderRefundResult(PaymentStatus.SUCCEEDED, providerRefundId)
        }

        override fun verifyWebhook(payload: String, headers: Map<String, String>): VerifiedProviderEvent {
            val providerPaymentId = payload.removePrefix("service-fee-succeeded:")
            require(providerPaymentId != payload) { "TEST_WEBHOOK_PAYLOAD_INVALID" }
            val payment = payments[providerPaymentId]
                ?: throw IllegalArgumentException("TEST_PROVIDER_PAYMENT_NOT_FOUND")
            return VerifiedProviderEvent(
                providerEventId = "test-event-$providerPaymentId",
                eventType = "PAYMENT_SUCCEEDED",
                providerPaymentId = providerPaymentId,
                providerTransactionId = "transaction-$providerPaymentId",
                paymentStatus = PaymentStatus.SUCCEEDED,
                amountMinor = payment.amountMinor,
                currency = payment.currency
            )
        }

        private data class PaymentSnapshot(val amountMinor: Long, val currency: String)
    }

    private data class TravelFlowFixture(
        val token: String,
        val userId: String,
        val adminId: String,
        val doctorId: String,
        val consultantId: String,
        val institutionId: String,
        val projectId: String,
        val institutionProjectId: String,
        val doctorInstitutionId: String,
        val priceConfigId: String,
        val membershipId: String
    ) {
        companion object {
            fun create(): TravelFlowFixture {
                val token = UUID.randomUUID().toString().replace("-", "").take(24)
                return TravelFlowFixture(
                    token = token,
                    userId = "usr-$token",
                    adminId = "adm-$token",
                    doctorId = "doc-$token",
                    consultantId = "con-$token",
                    institutionId = "ins-$token",
                    projectId = "prj-$token",
                    institutionProjectId = "ipr-$token",
                    doctorInstitutionId = "din-$token",
                    priceConfigId = "cfg-$token",
                    membershipId = "mem-$token"
                )
            }
        }
    }

    companion object {
        private val DATABASE = WorktreeTestDatabase.databaseName()

        @Container
        @ServiceConnection
        @JvmField
        val mysql = TravelGroundServiceFlowMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class TravelGroundServiceFlowMySqlContainer(imageName: String) :
    MySQLContainer<TravelGroundServiceFlowMySqlContainer>(imageName) {
    override fun start() {
        val configuredDatabaseName = this.databaseName
        require(configuredDatabaseName == WorktreeTestDatabase.databaseName()) {
            "Integration database must be derived from the current worktree: $configuredDatabaseName"
        }
        println("Travel service flow database host=$host, database=$configuredDatabaseName")
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
