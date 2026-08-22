package com.joysong.server.payment

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        "seed.demo.enabled=false",
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
                price = BigDecimal("1000.00")
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
                medicalListPrice = BigDecimal("1000.00")
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
