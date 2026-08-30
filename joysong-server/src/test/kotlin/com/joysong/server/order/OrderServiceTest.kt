package com.joysong.server.order

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.config.TravelGroundServicePricingProperties
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.InstitutionConsultant
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionProjectIdentity
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderBusinessNotificationDispatcher
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.order.service.TravelGroundServiceFeeRatePolicy
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.service.SettlementService
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.refund.service.RefundExecutionOutcome
import com.joysong.server.refund.service.RefundExecutionService
import com.joysong.server.review.service.ReviewService
import io.mockk.*
import io.mockk.impl.annotations.MockK
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class OrderServiceTest {

    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var projectRepository: ProjectRepository
    @MockK private lateinit var institutionProjectRepository: InstitutionProjectRepository
    @MockK private lateinit var institutionRepository: InstitutionRepository
    @MockK private lateinit var doctorProjectRepository: DoctorProjectRepository
    @MockK private lateinit var doctorRepository: DoctorRepository
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService
    @MockK private lateinit var couponService: CouponService
    @MockK private lateinit var settlementService: SettlementService
    @MockK private lateinit var entityManager: EntityManager
    @MockK private lateinit var refundRepository: RefundRepository
    @MockK private lateinit var reviewService: ReviewService
    @MockK private lateinit var institutionConsultantService: InstitutionConsultantService
    @MockK private lateinit var doctorInstitutionRelationshipService: DoctorInstitutionRelationshipService
    @MockK(relaxed = true) private lateinit var businessNotificationService: BusinessNotificationService
    @MockK(relaxed = true) private lateinit var orderBusinessNotificationDispatcher: OrderBusinessNotificationDispatcher
    private val institutionProjectDetailResolver = InstitutionProjectDetailResolver()

    private lateinit var orderService: OrderService

    private val testProject = ProjectEntity(
        id = "project-1",
        name = "热玛吉",
        coverImage = "project-cover.jpg",
        referencePrice = BigDecimal("5000.00")
    )

    private val testInstitutionProject = InstitutionProjectEntity(
        id = "inst-proj-1",
        institutionId = "inst-1",
        projectId = "project-1",
        price = BigDecimal("4500.00"),
        originalPrice = BigDecimal("6000.00"),
        coverImage = "inst-cover.jpg"
    )

    private val testInstitution = InstitutionEntity(
        id = "inst-1",
        name = "美丽机构"
    )

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        val splitProperties = OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }
        orderService = OrderService(
            orderRepository,
            projectRepository,
            institutionProjectRepository,
            institutionRepository,
            doctorProjectRepository,
            doctorRepository,
            orderStatusLogService,
            couponService,
            settlementService,
            entityManager,
            refundRepository,
            institutionProjectDetailResolver,
            reviewService,
            institutionConsultantService = institutionConsultantService,
            doctorInstitutionRelationshipService = doctorInstitutionRelationshipService,
            travelGroundServicePricing = travelGroundServicePricing(),
            businessNotificationService = businessNotificationService,
            orderBusinessNotificationDispatcher = orderBusinessNotificationDispatcher
        )
        // 默认 stub：logTransition 不做任何事
        justRun { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
        every { institutionConsultantService.requireApprovedConsultant(any(), any()) } returns
            InstitutionConsultant(
                id = "consultant-1",
                name = "测试咨询师",
                avatar = "consultant.png",
                institutionId = "inst-1",
                institutionName = "美丽机构"
            )
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(any(), any()) } returns DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal("4500.00")
        )
        every { institutionProjectRepository.findIdentityById(any()) } returns institutionProjectIdentity()
        every { institutionProjectRepository.findForUpdate(any()) } returns testInstitutionProject
        every { doctorProjectRepository.findForUpdate(any(), any()) } returns DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal("4500.00")
        )
        justRun { doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate(any(), any()) }
    }

    // ---- 创建订单 ----

    private fun institutionProjectIdentity(): InstitutionProjectIdentity = mockk<InstitutionProjectIdentity>().also { identity ->
        every { identity.id } returns "inst-proj-1"
        every { identity.institutionId } returns "inst-1"
        every { identity.projectId } returns "project-1"
        every { identity.version } returns 0
    }

    @Test
    fun `quote locks relationship institution project and doctor project in global order`() {
        val identity = institutionProjectIdentity()
        val binding = DoctorProjectEntity(
            doctorId = "doctor-1", projectId = "project-1", institutionProjectId = "inst-proj-1",
            price = BigDecimal("3999.00")
        )
        every { institutionProjectRepository.findIdentityById("inst-proj-1") } returns identity
        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns testInstitutionProject
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns binding

        val quote = orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")

        assertEquals(159_960L, quote.travelGroundServiceFeeMinor)
        verifyOrder {
            institutionProjectRepository.findIdentityById("inst-proj-1")
            doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate("doctor-1", "inst-1")
            institutionProjectRepository.findForUpdate("inst-proj-1")
            doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1")
        }
        verify(exactly = 0) {
            doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(any(), any())
        }
    }

    @Test
    fun `quote maps all availability failures but preserves non positive price error`() {
        val identity = institutionProjectIdentity()
        every { institutionProjectRepository.findIdentityById("inst-proj-1") } returns null
        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )

        every { institutionProjectRepository.findIdentityById("inst-proj-1") } returns identity

        every {
            doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate("doctor-1", "inst-1")
        } throws org.springframework.security.access.AccessDeniedException("inactive")
        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )

        justRun { doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate("doctor-1", "inst-1") }
        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns testInstitutionProject.copy(isActive = false)
        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )

        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns testInstitutionProject
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns null
        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )

        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns DoctorProjectEntity(
            doctorId = "doctor-1", projectId = "project-1", institutionProjectId = "inst-proj-1",
            price = BigDecimal("1000"), isActive = false
        )
        assertEquals(
            "DOCTOR_PROJECT_NOT_CONFIGURED",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )

        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns DoctorProjectEntity(
            doctorId = "doctor-1", projectId = "project-1", institutionProjectId = "inst-proj-1",
            price = BigDecimal.ZERO
        )
        assertEquals(
            "MEDICAL_LIST_PRICE_NOT_POSITIVE",
            assertThrows<IllegalArgumentException> {
                orderService.quoteTravelGroundService("doctor-1", "inst-proj-1")
            }.message
        )
    }

    @Test
    fun `new order rejects disabled doctor service before order or status log writes`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        val identity = institutionProjectIdentity()
        val inactiveBinding = DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal("4500.00"),
            isActive = false
        )
        every { institutionProjectRepository.findIdentityById("inst-proj-1") } returns identity
        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns testInstitutionProject
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns inactiveBinding
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns inactiveBinding
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity("doctor-1", "Doctor"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>() }

        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }

        assertEquals("所选医生服务已停用，暂不可预约", error.message)
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }

        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns inactiveBinding.copy(isActive = true)

        val reactivated = orderService.createOrder("user-1", request)

        assertEquals(OrderStatusEnum.PENDING_SERVICE_FEE.value, reactivated.status)
        assertEquals(450_000L, reactivated.medicalListPriceMinor)
        verify(exactly = 1) { orderRepository.save(any()) }
        verify(exactly = 1) { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `new order rejects inactive institution project before order or status log writes`() {
        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns
            testInstitutionProject.copy(isActive = false)

        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder(
                "user-1",
                CreateOrderRequest(
                    projectId = "project-1",
                    institutionProjectId = "inst-proj-1",
                    doctorId = "doctor-1",
                    consultantId = "consultant-1"
                )
            )
        }

        assertEquals("机构项目已停用，暂不可预约", error.message)
        verify(exactly = 0) { doctorProjectRepository.findForUpdate(any(), any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `机构法人不能查看所属机构订单`() {
        val order = OrderEntity(
            id = "order-1",
            orderNo = "ORDER-1",
            userId = "user-1",
            projectName = "项目一",
            institutionId = "inst-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            price = BigDecimal("100.00"),
            status = "PAID"
        )
        every { orderRepository.findById("order-1") } returns Optional.of(order)
        val actor = ManagementActor(
            userId = "legal-1",
            isAdmin = false,
            activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
            doctorId = null,
            managedInstitutionIds = setOf("inst-1"),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )

        assertThrows<org.springframework.security.access.AccessDeniedException> {
            orderService.requireOrderForManagement(actor, "order-1")
        }
    }

    @Test
    fun `money linked order cannot be hard deleted`() {
        val order = OrderEntity(
            id = "order-money", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "COMPLETED"
        )
        every { orderRepository.findByIdIncludeDeleted("order-money") } returns order
        every { orderRepository.hasMoneyReferences("order-money") } returns true

        val error = assertThrows<IllegalArgumentException> {
            orderService.adminHardDeleteOrder("order-money")
        }

        assertEquals("订单已关联支付、退款或结算账本记录，禁止物理删除", error.message)
        verify(exactly = 0) { entityManager.createNativeQuery(any()) }
    }

    @Test
    fun `order without money references remains hard deletable`() {
        val order = OrderEntity(
            id = "order-empty", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "CANCELLED"
        )
        val query = mockk<Query>()
        every { orderRepository.findByIdIncludeDeleted("order-empty") } returns order
        every { orderRepository.hasMoneyReferences("order-empty") } returns false
        every { entityManager.createNativeQuery("DELETE FROM orders WHERE id = :id") } returns query
        every { query.setParameter("id", "order-empty") } returns query
        every { query.executeUpdate() } returns 1

        orderService.adminHardDeleteOrder("order-empty")

        verify(exactly = 1) { query.executeUpdate() }
    }

    @Test
    fun `创建订单 - 缺少医生时拒绝`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            consultantId = "consultant-1"
        )

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>() }

        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }

        assertEquals("订单必须关联医生", error.message)
    }

    @Test
    fun `创建订单 - 缺少机构项目时拒绝`() {
        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder(
                "user-1",
                CreateOrderRequest(
                    projectId = "project-1",
                    doctorId = "doctor-1",
                    consultantId = "consultant-1"
                )
            )
        }

        assertEquals("订单必须关联机构项目", error.message)
    }

    @Test
    fun `创建订单 - 缺少医美顾问时拒绝`() {
        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder(
                "user-1",
                CreateOrderRequest(
                    projectId = "project-1",
                    institutionProjectId = "inst-proj-1",
                    doctorId = "doctor-1"
                )
            )
        }

        assertEquals("订单必须关联医美顾问", error.message)
    }

    @Test
    fun `创建订单 - 医生执业关系已撤销时拒绝`() {
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every {
            doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate("doctor-1", "inst-1")
        } throws org.springframework.security.access.AccessDeniedException("医生与机构的有效执业关系已失效")

        val error = assertThrows<org.springframework.security.access.AccessDeniedException> {
            orderService.createOrder(
                "user-1",
                CreateOrderRequest(
                    projectId = "project-1",
                    institutionProjectId = "inst-proj-1",
                    doctorId = "doctor-1",
                    consultantId = "consultant-1"
                )
            )
        }

        assertEquals("医生与机构的有效执业关系已失效", error.message)
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `创建订单 - 医美顾问名称为空时拒绝`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns true
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { institutionConsultantService.requireApprovedConsultant("inst-1", "consultant-1") } returns
            InstitutionConsultant("consultant-1", " ")

        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }

        assertEquals("医美顾问名称不能为空", error.message)
    }

    @Test
    fun `new order persists and returns the complete immutable pricing quote`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns true
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-new") }

        val result = orderService.createOrder("user-1", request)
        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }

        assertEquals("TRAVEL_GROUND_SERVICE_ONLY", saved.captured.paymentFlow)
        assertEquals(OrderStatusEnum.PENDING_SERVICE_FEE.value, saved.captured.status)
        assertEquals(450_000L, saved.captured.medicalListPriceMinor)
        assertEquals(4_000, saved.captured.platformServiceRateBps)
        assertEquals(180_000L, saved.captured.travelGroundServiceFeeMinor)
        assertEquals("travel-ground-service-rate:0.400000", saved.captured.pricingPolicyRevision)
        assertEquals(BigDecimal("1800.00"), saved.captured.price)
        assertEquals(180_000L, saved.captured.totalAmountMinor)
        assertEquals("USD", saved.captured.currency)
        assertEquals(1, saved.captured.quantity)
        assertNull(saved.captured.couponId)
        assertNull(saved.captured.userCouponId)
        assertEquals(BigDecimal.ZERO, saved.captured.consultationFee)
        assertEquals(BigDecimal.ZERO, saved.captured.remainingAmount)
        assertTrue(result.consultantBound)
        assertNull(result.institutionId)
        assertNull(result.institutionName)
        assertNull(result.consultantId)
        assertNull(result.consultantName)
        assertNull(result.consultantAvatar)
        assertEquals("USD", result.currency)
        assertEquals(450_000L, result.medicalListPriceMinor)
        assertEquals(4_000, result.platformServiceRateBps)
        assertEquals(180_000L, result.travelGroundServiceFeeMinor)
        assertEquals("travel-ground-service-rate:0.400000", result.pricingPolicyRevision)
        assertEquals("doctor-1", result.doctorId)
        assertEquals("测试医生", result.doctorName)
        assertFalse(result.serviceActivated)
        assertFalse(result.consultantDetailsVisible)
        assertFalse(result.serviceConversationReadable)
        assertFalse(result.serviceMessagingEnabled)
        verify(exactly = 0) { couponService.listUserAvailableCoupons(any()) }
        verify(exactly = 0) { couponService.calculateDiscount(any(), any()) }
        verify(exactly = 0) { couponService.redeemCoupon(any(), any()) }
        verify(exactly = 1) {
            businessNotificationService.orderCreated("order-new", "user-1")
        }
    }

    @Test
    fun `new order uses the selected doctor project price as the only fee basis`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal("3999.00")
        )
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-1") }

        val result = orderService.createOrder("user-1", request)

        assertEquals(399_900L, result.medicalListPriceMinor)
        assertEquals(4_000, result.platformServiceRateBps)
        assertEquals(159_960L, result.travelGroundServiceFeeMinor)

        verify { orderStatusLogService.logTransition("order-1", "", OrderStatusEnum.PENDING_SERVICE_FEE.value, "user-1", "USER", any()) }
    }

    @Test
    fun `new order succeeds without a legacy medical config`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal("3999.00")
        )
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-no-config") }

        val created = orderService.createOrder("user-1", request)

        assertEquals(399_900L, created.medicalListPriceMinor)
        assertEquals(159_960L, created.travelGroundServiceFeeMinor)
    }

    @Test
    fun `创建订单 - 无机构项目时拒绝`() {
        val request = CreateOrderRequest(projectId = "project-1", doctorId = "doctor-1", consultantId = "consultant-1")

        val error = assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }

        assertEquals("订单必须关联机构项目", error.message)
    }

    @Test
    fun `创建订单 - 锁定校验发现医生机构关系失效时不保存订单`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every {
            doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate("doctor-1", "inst-1")
        } throws org.springframework.security.access.AccessDeniedException("医生与机构的有效执业关系已失效")

        assertThrows<org.springframework.security.access.AccessDeniedException> {
            orderService.createOrder("user-1", request)
        }

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `创建订单 - 医生项目价格为零时拒绝`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        val doctorProject = DoctorProjectEntity(
            doctorId = "doctor-1",
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            price = BigDecimal.ZERO
        )

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.findForUpdate("doctor-1", "inst-proj-1") } returns doctorProject
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        val error = assertThrows<IllegalArgumentException> { orderService.createOrder("user-1", request) }

        assertEquals("MEDICAL_LIST_PRICE_NOT_POSITIVE", error.message)
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `创建订单 - 项目不存在抛出异常`() {
        val request = CreateOrderRequest(
            projectId = "non-exist",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )
        every { projectRepository.findById("non-exist") } returns Optional.empty()

        assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }
    }

    @Test
    fun `创建订单 - 机构项目封面为空时使用项目封面`() {
        val instProjNoImage = testInstitutionProject.copy(coverImage = "")
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            consultantId = "consultant-1"
        )

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findForUpdate("inst-proj-1") } returns instProjNoImage
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns true
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-4") }

        val result = orderService.createOrder("user-1", request)

        assertEquals("project-cover.jpg", result.coverImage)
    }

    // ---- 获取订单 ----

    @Test
    fun `获取用户订单列表`() {
        val orders = listOf(
            createTestOrder("o1", "user-1"),
            createTestOrder("o2", "user-1")
        )
        every { orderRepository.findByUserIdOrderByCreatedAtDesc("user-1") } returns orders

        val result = orderService.getOrdersByUser("user-1")

        assertEquals(2, result.size)
    }

    @Test
    fun `按状态获取用户订单`() {
        val orders = listOf(createTestOrder("o1", "user-1", status = "PENDING_PAYMENT"))
        every { orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc("user-1", "PENDING_PAYMENT") } returns orders

        val result = orderService.getOrdersByUserAndStatus("user-1", "PENDING_PAYMENT")

        assertEquals(1, result.size)
    }

    @Test
    fun `获取订单详情 - 本人订单返回数据`() {
        val order = createTestOrder("o1", "user-1")
        every { orderRepository.findById("o1") } returns Optional.of(order)

        val result = orderService.getOrderById("o1", "user-1")

        assertNotNull(result)
        assertEquals("o1", result?.id)
    }

    @Test
    fun `existing order snapshot remains unchanged after doctor service is disabled`() {
        val snapshot = createTestOrder(
            id = "existing-order",
            userId = "user-1",
            status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        ).copy(
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1",
            doctorName = "测试医生",
            currency = "USD",
            medicalListPriceMinor = 450_000,
            platformServiceRateBps = 4_000,
            travelGroundServiceFeeMinor = 180_000,
            totalAmountMinor = 180_000
        )
        every { orderRepository.findById("existing-order") } returns Optional.of(snapshot)

        val result = orderService.getOrderById("existing-order", "user-1")

        assertEquals(snapshot, result)
        verify(exactly = 0) { doctorProjectRepository.findForUpdate(any(), any()) }
        verify(exactly = 0) {
            doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(any(), any())
        }
    }

    @Test
    fun `获取订单详情 - 非本人订单返回 null`() {
        val order = createTestOrder("o1", "user-1")
        every { orderRepository.findById("o1") } returns Optional.of(order)

        val result = orderService.getOrderById("o1", "user-2")

        assertNull(result)
    }

    // ---- 核销码 ----

    @Test
    fun `requestVerification 生成6位核销码`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.requestVerification("o1", "user-1")

        // 验证 save 时传入了 6 位核销码
        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertNotNull(saved.captured.verifyCode)
        assertEquals(6, saved.captured.verifyCode?.length)
    }

    @Test
    fun `requestVerification 非本人订单抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.requestVerification("o1", "user-2")
        }
    }

    // ---- 确认到店核验 ----

    @Test
    fun `confirmVerification 状态变更为 VERIFIED`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        val result = orderService.confirmVerification("o1", "inst-user-1", "123456")

        assertEquals(OrderStatusEnum.VERIFIED.value, result.status)
        verify { orderStatusLogService.logTransition("o1", OrderStatusEnum.CONSULTATION_PAID.value, OrderStatusEnum.VERIFIED.value, "inst-user-1", "INSTITUTION", any()) }
    }

    @Test
    fun `confirmVerification 非法状态转换抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<com.joysong.server.order.service.OrderManagementConflictException> {
            orderService.confirmVerification("o1", "inst-user-1", "123456")
        }
    }

    @Test
    fun `管理员可以查看全部专业订单列表和详情`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        val actor = ManagementActor(
            userId = "admin-1",
            isAdmin = true,
            activeRoles = setOf("ADMIN"),
            doctorId = null,
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )

        every { orderRepository.findManagementOrders(null, null, any()) } returns
            org.springframework.data.domain.PageImpl(listOf(order))
        every { orderRepository.findById(order.id) } returns Optional.of(order)

        assertEquals(listOf(order.id), orderService.getOrdersForManagement(actor, null, 0, 20).map { it.id })
        assertEquals(order.id, orderService.requireOrderForManagement(actor, order.id).id)
    }

    @Test
    fun `管理员可以核销和申请完成任意医生订单`() {
        val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
        val verificationOrder = createTestOrder("verify-order", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
            .copy(doctorId = "doctor-2", verifyCode = "123456")
        val completionOrder = createTestOrder("completion-order", "user-1", status = OrderStatusEnum.BALANCE_PAID.value)
            .copy(doctorId = "doctor-3", verifyCode = "654321")
        every { orderRepository.findByIdForUpdate("verify-order") } returns verificationOrder
        every { orderRepository.findByIdForUpdate("completion-order") } returns completionOrder
        every { orderRepository.save(any()) } answers { firstArg() }

        assertEquals(OrderStatusEnum.VERIFIED.value,
            orderService.confirmVerificationForManagement(actor, "verify-order", "123456").status)
        assertEquals(OrderStatusEnum.PENDING_COMPLETION.value,
            orderService.requestCompletionForManagement(actor, "completion-order", "654321").status)
    }

    @Test
    fun `专业订单列表使用精确 offset 且仅查询医生本人`() {
        val pageable = slot<org.springframework.data.domain.Pageable>()
        val actor = ManagementActor("doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1"))
        every { orderRepository.findManagementOrders("doctor-1", null, capture(pageable)) } returns
            org.springframework.data.domain.PageImpl(List(20) { createTestOrder("o${it + 16}", "user-1") })

        val result = orderService.getOrdersForManagement(actor, null, 15, 20)

        assertEquals(15, pageable.captured.offset)
        assertEquals(20, result.size)
    }

    @Test
    fun `confirmVerification replay is idempotent and does not log`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
            .copy(verifiedAt = LocalDateTime.now(), verifyCode = null)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        val result = orderService.confirmVerification("o1", "doctor-1", "123456")

        assertEquals(OrderStatusEnum.VERIFIED.value, result.status)
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    // ---- 确认完成 ----

    @Test
    fun `confirmCompletion 用户确认完成并触发结算`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_COMPLETION.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }
        every { settlementService.saveSettlement("o1", any()) } returns mockk()

        val result = orderService.confirmCompletion("o1", "user-1")

        assertEquals(OrderStatusEnum.COMPLETED.value, result.status)
        // 验证 save 时设置了 settlementAt
        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertNotNull(saved.captured.settlementAt)
        verify { settlementService.saveSettlement("o1", saved.captured.settlementAt) }
        verify(exactly = 1) {
            businessNotificationService.orderCompleted("o1", "consultant-1", "doctor-1", "inst-1")
        }
    }

    @Test
    fun `travel active confirmation completes without settlement and is idempotent`() {
        var current = createTestOrder(
            "travel-completion",
            "user-1",
            status = OrderStatusEnum.SERVICE_ACTIVE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        ).copy(serviceActivatedAt = LocalDateTime.now())
        every { orderRepository.findById("travel-completion") } returns Optional.of(current)
        every { orderRepository.findByIdForUpdate("travel-completion") } answers { current }
        every { orderRepository.save(any()) } answers {
            firstArg<OrderEntity>().also { current = it }
        }

        val first = orderService.confirmCompletion("travel-completion", "user-1")
        val second = orderService.confirmCompletion("travel-completion", "user-1")

        assertEquals(OrderStatusEnum.COMPLETED.value, first.status)
        assertEquals(OrderStatusEnum.COMPLETED.value, second.status)
        assertNotNull(first.completedAt)
        verify(exactly = 1) {
            orderStatusLogService.logTransition(
                "travel-completion",
                OrderStatusEnum.SERVICE_ACTIVE.value,
                OrderStatusEnum.COMPLETED.value,
                "user-1",
                "USER",
                "用户确认旅游地接服务完成"
            )
        }
        verify(exactly = 0) { settlementService.saveSettlement(any(), any()) }
        verify(exactly = 1) {
            businessNotificationService.orderCompleted(
                "travel-completion", "consultant-1", "doctor-1", "inst-1"
            )
        }
    }

    @Test
    fun `confirmCompletion 非本人操作抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_COMPLETION.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<IllegalArgumentException> {
            orderService.confirmCompletion("o1", "user-2")
        }
    }

    // ---- 取消订单 ----

    @Test
    fun `cancelOrder 用户取消待支付订单 - 保留审计并转为已取消`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelOrder("o1", "user-1")

        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
        verify {
            orderStatusLogService.logTransition(
                orderId = "o1",
                fromStatus = OrderStatusEnum.PENDING_PAYMENT.value,
                toStatus = OrderStatusEnum.CANCELLED.value,
                operatorId = "user-1",
                operatorType = "USER",
                remark = "用户取消待支付订单"
            )
        }
        verify(exactly = 0) { entityManager.createNativeQuery(any<String>()) }
        verify(exactly = 1) {
            businessNotificationService.orderCancelled("o1", "user-1", "consultant-1")
        }
    }

    @Test
    fun `cancelOrder 用户可取消待支付旅游地接服务费订单并记录实际原状态`() {
        val order = createTestOrder(
            "o1",
            "user-1",
            status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        )
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelOrder("o1", "user-1")

        verify {
            orderRepository.save(match { it.status == OrderStatusEnum.CANCELLED.value })
            orderStatusLogService.logTransition(
                orderId = "o1",
                fromStatus = OrderStatusEnum.PENDING_SERVICE_FEE.value,
                toStatus = OrderStatusEnum.CANCELLED.value,
                operatorId = "user-1",
                operatorType = "USER",
                remark = "用户取消待支付订单"
            )
        }
    }

    @Test
    fun `success first makes user cancellation lose the locked race without overwriting activation`() {
        val activatedAt = LocalDateTime.of(2026, 8, 22, 12, 0)
        val active = createTestOrder(
            "travel-active",
            "user-1",
            status = OrderStatusEnum.SERVICE_ACTIVE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        ).copy(serviceActivatedAt = activatedAt)
        every { orderRepository.findByIdForUpdate("travel-active") } returns active

        val error = assertThrows<RuntimeException> {
            orderService.cancelOrder("travel-active", "user-1")
        }

        assertEquals("当前状态不允许取消", error.message)
        verify(exactly = 1) { orderRepository.findByIdForUpdate("travel-active") }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) {
            orderStatusLogService.logTransition(
                "travel-active", any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `cancelOrder 非本人订单抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<RuntimeException> {
            orderService.cancelOrder("o1", "user-2")
        }
    }

    @Test
    fun `cancelOrder 非待支付状态不允许取消`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<RuntimeException> {
            orderService.cancelOrder("o1", "user-1")
        }
    }

    // ---- 超时自动取消 ----

    @Test
    fun `cancelExpiredPendingOrder 取消超时未支付订单`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelExpiredPendingOrder("o1")

        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
    }

    @Test
    fun `cancelExpiredPendingOrder closes pending service fee order under row lock`() {
        val order = createTestOrder(
            "travel-pending",
            "user-1",
            status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        )
        every { orderRepository.findByIdForUpdate("travel-pending") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelExpiredPendingOrder("travel-pending")

        val saved = slot<OrderEntity>()
        verify(exactly = 1) { orderRepository.findByIdForUpdate("travel-pending") }
        verify(exactly = 1) { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
        verify(exactly = 1) {
            orderStatusLogService.logTransition(
                "travel-pending",
                OrderStatusEnum.PENDING_SERVICE_FEE.value,
                OrderStatusEnum.CANCELLED.value,
                null,
                "SYSTEM",
                "支付超时自动取消"
            )
        }
        verify(exactly = 1) {
            businessNotificationService.orderCancelled("travel-pending", "user-1", "consultant-1")
        }
    }

    @Test
    fun `cancelExpiredPendingOrder 非 PENDING_PAYMENT 状态跳过`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        orderService.cancelExpiredPendingOrder("o1")

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `cancelBalanceTimeoutOrder 取消尾款超时订单`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelBalanceTimeoutOrder("o1")

        val saved = slot<OrderEntity>()
        verify(exactly = 1) { orderRepository.findByIdForUpdate("o1") }
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
        verify(exactly = 1) {
            orderBusinessNotificationDispatcher.orderCancelled("o1", "user-1", "consultant-1")
        }
    }

    @Test
    fun `cancelBalanceTimeoutOrder 非 VERIFIED 状态跳过`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        orderService.cancelBalanceTimeoutOrder("o1")

        verify(exactly = 1) { orderRepository.findByIdForUpdate("o1") }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `cancelExpiredConsultationPaidOrder emits one refunded notification for the real transition only`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
            .copy(paidAmount = BigDecimal("100.00"), paidAmountMinor = 10_000)
        every { orderRepository.findByIdForUpdate("o1") } returnsMany listOf(
            order,
            order.copy(status = OrderStatusEnum.REFUNDED.value)
        )
        every { orderRepository.save(any()) } answers { firstArg() }
        every { refundRepository.save(any()) } answers { firstArg() }

        orderService.cancelExpiredConsultationPaidOrder("o1")
        orderService.cancelExpiredConsultationPaidOrder("o1")

        verify(exactly = 1) { orderRepository.save(match { it.status == OrderStatusEnum.REFUNDED.value }) }
        verify(exactly = 0) { orderBusinessNotificationDispatcher.orderCancelled(any(), any(), any()) }
        verify(exactly = 1) {
            orderBusinessNotificationDispatcher.orderRefunded("o1", "user-1", "consultant-1", "doctor-1")
        }
    }

    @Test
    fun `failed balance-timeout notification rolls back independently after the refund business commit`() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:order_timeout_notification_failure;DB_CLOSE_DELAY=-1",
            "sa",
            ""
        )
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE business_events (id INT PRIMARY KEY)")
        jdbc.execute("CREATE TABLE notification_events (id INT PRIMARY KEY)")
        val transactionManager = CountingDataSourceTransactionManager(dataSource)
        val notificationService = mockk<BusinessNotificationService>(relaxed = true)
        every { notificationService.orderCancelled("o1", "user-1", "consultant-1") } answers {
            jdbc.update("INSERT INTO notification_events (id) VALUES (1)")
            throw IllegalStateException("notification persistence unavailable")
        }
        val refundExecutionService = mockk<RefundExecutionService>()
        every { refundExecutionService.execute(any()) } returns RefundExecutionOutcome(
            refundedAmountMinor = 10_000,
            completed = true
        )
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value).copy(
            consultationFee = BigDecimal("100.00"),
            paidAmount = BigDecimal("100.00"),
            paidAmountMinor = 10_000
        )
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }
        every { refundRepository.save(any()) } answers { firstArg() }
        val service = orderService(
            businessNotificationService = notificationService,
            refundExecutionService = refundExecutionService,
            orderBusinessNotificationDispatcher = proxiedNotificationDispatcher(
                notificationService,
                transactionManager
            )
        )

        assertDoesNotThrow {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.update("INSERT INTO business_events (id) VALUES (1)")
                service.cancelBalanceTimeoutOrder("o1")
            }
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_events", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_events", Int::class.java))
        assertEquals(2, transactionManager.begins)
        verify(exactly = 1) { refundExecutionService.execute(any()) }
    }

    // ---- 删除订单 ----

    @Test
    fun `deleteOrder 已取消状态可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CANCELLED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        justRun { orderRepository.deleteById("o1") }

        val result = orderService.deleteOrder("o1", "user-1")

        assertTrue(result)
        verify { orderRepository.deleteById("o1") }
    }

    @Test
    fun `admin soft delete locks the order before deletion`() {
        val order = createTestOrder("admin-delete", "user-1", status = OrderStatusEnum.CANCELLED.value)
        every { orderRepository.findByIdForUpdate("admin-delete") } returns order
        justRun { orderRepository.deleteById("admin-delete") }

        orderService.adminDeleteById("admin-delete")

        verify(exactly = 1) { orderRepository.findByIdForUpdate("admin-delete") }
        verify(exactly = 1) { orderRepository.deleteById("admin-delete") }
    }

    @Test
    fun `deleteOrder 已完成状态可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.COMPLETED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        justRun { orderRepository.deleteById("o1") }

        assertTrue(orderService.deleteOrder("o1", "user-1"))
    }

    @Test
    fun `deleteOrder 旅游地接已完成订单不可删除`() {
        val order = createTestOrder(
            "travel-completed",
            "user-1",
            status = OrderStatusEnum.COMPLETED.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        )
        every { orderRepository.findByIdForUpdate("travel-completed") } returns order
        justRun { orderRepository.deleteById("travel-completed") }

        val error = assertThrows<IllegalArgumentException> {
            orderService.deleteOrder("travel-completed", "user-1")
        }

        assertEquals("旅游地接服务已完成订单仍可申请退款，暂不可删除", error.message)
        verify(exactly = 0) { orderRepository.deleteById(any()) }
    }

    @Test
    fun `deleteOrder 待支付状态不可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<IllegalArgumentException> {
            orderService.deleteOrder("o1", "user-1")
        }
    }

    @Test
    fun `deleteOrder 非本人订单抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CANCELLED.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<IllegalArgumentException> {
            orderService.deleteOrder("o1", "user-2")
        }
    }

    @Test
    fun `deleteOrder 不存在的订单返回 false`() {
        every { orderRepository.findByIdForUpdate("o1") } returns null

        assertFalse(orderService.deleteOrder("o1", "user-1"))
    }

    // ---- 管理员操作 ----

    @Test
    fun `adminUpdateStatus 合法状态变更`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        val result = orderService.adminUpdateStatus("o1", OrderStatusEnum.CONSULTATION_PAID.value)

        assertNotNull(result)
        assertEquals(OrderStatusEnum.CONSULTATION_PAID.value, result?.status)
        verify { orderStatusLogService.logTransition("o1", OrderStatusEnum.PENDING_PAYMENT.value, OrderStatusEnum.CONSULTATION_PAID.value, null, "ADMIN", any()) }
    }

    @Test
    fun `adminUpdateStatus 非法状态变更抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findByIdForUpdate("o1") } returns order

        assertThrows<IllegalArgumentException> {
            orderService.adminUpdateStatus("o1", OrderStatusEnum.COMPLETED.value)
        }
    }

    @Test
    fun `adminUpdateStatus does not allow a legacy completed order into travel refund review`() {
        val order = createTestOrder("legacy-completed", "user-1", status = OrderStatusEnum.COMPLETED.value)
        every { orderRepository.findByIdForUpdate("legacy-completed") } returns order

        assertThrows<IllegalArgumentException> {
            orderService.adminUpdateStatus("legacy-completed", OrderStatusEnum.REFUND_REVIEW.value)
        }

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `adminUpdateStatus 通用入口不能伪造旅游地接服务激活或退款推进`() {
        val protectedTransitions = listOf(
            OrderStatusEnum.PENDING_SERVICE_FEE to OrderStatusEnum.SERVICE_ACTIVE,
            OrderStatusEnum.SERVICE_ACTIVE to OrderStatusEnum.REFUND_REVIEW,
            OrderStatusEnum.REFUND_REVIEW to OrderStatusEnum.REFUND_PROCESSING,
            OrderStatusEnum.REFUND_PROCESSING to OrderStatusEnum.REFUNDED
        )
        every { orderRepository.save(any()) } answers { firstArg() }

        protectedTransitions.forEachIndexed { index, (from, target) ->
            val orderId = "travel-$index"
            every { orderRepository.findByIdForUpdate(orderId) } returns
                createTestOrder(
                    orderId,
                    "user-1",
                    status = from.value,
                    paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
                )

            assertThrows<IllegalArgumentException> {
                orderService.adminUpdateStatus(orderId, target.value)
            }
        }

        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) {
            orderStatusLogService.logTransition(
                match { it.startsWith("travel-") }, any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `adminUpdateStatus 通用入口仅允许安全取消待支付旅游地接服务订单`() {
        val order = createTestOrder(
            "travel-1",
            "user-1",
            status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        )
        every { orderRepository.findByIdForUpdate("travel-1") } returns order
        every { orderRepository.save(any()) } answers { firstArg() }

        val updated = orderService.adminUpdateStatus("travel-1", OrderStatusEnum.CANCELLED.value)

        assertEquals(OrderStatusEnum.CANCELLED.value, updated?.status)
        verify {
            orderStatusLogService.logTransition(
                "travel-1",
                OrderStatusEnum.PENDING_SERVICE_FEE.value,
                OrderStatusEnum.CANCELLED.value,
                null,
                "ADMIN",
                "管理员修改状态"
            )
        }
        verify(exactly = 1) {
            businessNotificationService.orderCancelled("travel-1", "user-1", "consultant-1")
        }
    }

    @Test
    fun `adminManualVerify 手动核销会写入核验时间并清除核销码`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
            .copy(verifyCode = "123456")
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        val result = orderService.adminManualVerify("o1", "admin-1")

        assertEquals(OrderStatusEnum.VERIFIED.value, result.status)
        assertNotNull(result.verifiedAt)
        assertNull(result.verifyCode)
        verify {
            orderStatusLogService.logTransition(
                "o1",
                OrderStatusEnum.CONSULTATION_PAID.value,
                OrderStatusEnum.VERIFIED.value,
                "admin-1",
                "ADMIN",
                "管理员手动完成机构核销（测试）"
            )
        }
    }

    @Test
    fun `adminManualVerify 拒绝非面诊金已付订单`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.adminManualVerify("o1", "admin-1")
        }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `旅游地接服务订单显式拒绝全部医疗核验完成和结算入口`() {
        val order = createTestOrder(
            "travel-1",
            "user-1",
            status = OrderStatusEnum.SERVICE_ACTIVE.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY"
        ).copy(serviceActivatedAt = LocalDateTime.now())
        val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
        every { orderRepository.findById("travel-1") } returns Optional.of(order)
        every { orderRepository.findByIdForUpdate("travel-1") } returns order

        val medicalActions = listOf<() -> Unit>(
            { orderService.requestVerification("travel-1", "user-1") },
            { orderService.confirmVerification("travel-1", "operator-1", "123456") },
            { orderService.confirmVerificationForManagement(actor, "travel-1", "123456") },
            { orderService.requestCompletion("travel-1", "operator-1", "123456") },
            { orderService.requestCompletionForManagement(actor, "travel-1", "123456") },
            { orderService.adminManualVerify("travel-1", "admin-1") },
            { orderService.adminUpdateStatus("travel-1", OrderStatusEnum.COMPLETED.value) },
            { orderService.autoCompleteReview("travel-1") }
        )

        medicalActions.forEach { action ->
            val error = assertThrows<IllegalArgumentException> { action() }
            assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", error.message)
        }
        verify(exactly = 0) { orderRepository.save(any()) }
        verify(exactly = 0) { settlementService.saveSettlement(any(), any()) }
        verify(exactly = 0) { reviewService.submitAutomaticReview(any()) }
    }

    @Test
    fun `autoCompleteReview 超时自动好评并触发结算`() {
        every { orderRepository.findById("o1") } returns Optional.of(
            createTestOrder("o1", "user-1", OrderStatusEnum.COMPLETED.value)
        )
        every { reviewService.submitAutomaticReview("o1") } returns mockk()

        orderService.autoCompleteReview("o1")

        verify { reviewService.submitAutomaticReview("o1") }
    }

    @Test
    fun `autoCompleteReview 不可转换状态跳过`() {
        every { orderRepository.findById("o1") } returns Optional.of(
            createTestOrder("o1", "user-1", OrderStatusEnum.COMPLETED.value)
        )
        every { reviewService.submitAutomaticReview("o1") } returns null

        orderService.autoCompleteReview("o1")

        verify { reviewService.submitAutomaticReview("o1") }
    }

    // ---- 辅助方法 ----

    private fun createTestOrder(
        id: String,
        userId: String,
        status: String = OrderStatusEnum.PENDING_PAYMENT.value,
        paymentFlow: String = "LEGACY_MEDICAL"
    ): OrderEntity = OrderEntity(
        id = id,
        userId = userId,
        projectName = "热玛吉",
        institutionName = "美丽机构",
        coverImage = "cover.jpg",
        price = BigDecimal("4500.00"),
        paidAmount = BigDecimal.ZERO,
        status = status,
        paymentFlow = paymentFlow,
        projectId = "project-1",
        institutionId = "inst-1",
        consultantId = "consultant-1",
        doctorId = "doctor-1",
        verifyCode = "123456",
        orderNo = "JOY202607311200001234"
    )

    private fun orderService(
        businessNotificationService: BusinessNotificationService,
        refundExecutionService: RefundExecutionService,
        orderBusinessNotificationDispatcher: OrderBusinessNotificationDispatcher
    ) = OrderService(
        orderRepository,
        projectRepository,
        institutionProjectRepository,
        institutionRepository,
        doctorProjectRepository,
        doctorRepository,
        orderStatusLogService,
        couponService,
        settlementService,
        entityManager,
        refundRepository,
        institutionProjectDetailResolver,
        reviewService,
        institutionConsultantService = institutionConsultantService,
        doctorInstitutionRelationshipService = doctorInstitutionRelationshipService,
        travelGroundServicePricing = TravelGroundServicePricing(
            TravelGroundServiceFeeRatePolicy(TravelGroundServicePricingProperties())
        ),
        businessNotificationService = businessNotificationService,
        orderBusinessNotificationDispatcher = orderBusinessNotificationDispatcher,
        refundExecutionService = refundExecutionService
    )

    private fun travelGroundServicePricing() = TravelGroundServicePricing(
        TravelGroundServiceFeeRatePolicy(TravelGroundServicePricingProperties())
    )

    private fun proxiedNotificationDispatcher(
        businessNotificationService: BusinessNotificationService,
        transactionManager: DataSourceTransactionManager
    ): OrderBusinessNotificationDispatcher {
        val transactionAdvice = TransactionInterceptor(
            transactionManager as TransactionManager,
            AnnotationTransactionAttributeSource()
        )
        return (ProxyFactory(OrderBusinessNotificationDispatcher(businessNotificationService)).apply {
            isProxyTargetClass = true
            addAdvice(transactionAdvice)
        }.proxy as OrderBusinessNotificationDispatcher).also { dispatcher ->
            assertTrue(AopUtils.isAopProxy(dispatcher))
        }
    }

    private class CountingDataSourceTransactionManager(dataSource: javax.sql.DataSource) :
        DataSourceTransactionManager(dataSource) {
        var begins = 0

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            begins += 1
            super.doBegin(transaction, definition)
        }

        override fun doRollback(status: DefaultTransactionStatus) {
            super.doRollback(status)
        }
    }
}
