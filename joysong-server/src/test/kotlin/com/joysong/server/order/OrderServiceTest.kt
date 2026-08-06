package com.joysong.server.order

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.service.SettlementService
import com.joysong.server.refund.repository.RefundRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class OrderServiceTest {

    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var projectRepository: ProjectRepository
    @MockK private lateinit var institutionProjectRepository: InstitutionProjectRepository
    @MockK private lateinit var institutionRepository: InstitutionRepository
    @MockK private lateinit var doctorInstitutionProjectConfigRepository: DoctorInstitutionProjectConfigRepository
    @MockK private lateinit var doctorProjectRepository: DoctorProjectRepository
    @MockK private lateinit var doctorRepository: DoctorRepository
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService
    @MockK private lateinit var couponService: CouponService
    @MockK private lateinit var settlementService: SettlementService
    @MockK private lateinit var entityManager: EntityManager
    @MockK private lateinit var refundRepository: RefundRepository
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
        orderService = OrderService(
            orderRepository,
            projectRepository,
            institutionProjectRepository,
            institutionRepository,
            doctorInstitutionProjectConfigRepository,
            doctorProjectRepository,
            doctorRepository,
            orderStatusLogService,
            couponService,
            settlementService,
            entityManager,
            refundRepository,
            institutionProjectDetailResolver
        )
        // 默认 stub：logTransition 不做任何事
        justRun { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    // ---- 创建订单 ----

    @Test
    fun `创建订单 - 使用机构项目价格，无面诊金无优惠券`() {
        val request = CreateOrderRequest(projectId = "project-1", institutionProjectId = "inst-proj-1")

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns true
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-1") }

        val result = orderService.createOrder("user-1", request)

        assertEquals("project-1", result.projectId)
        assertEquals("inst-1", result.institutionId)
        assertEquals("美丽机构", result.institutionName)
        assertEquals(BigDecimal("4500.00"), result.amount)
        assertEquals(BigDecimal.ZERO, result.paidAmount)
        assertEquals(BigDecimal.ZERO, result.consultationFee)
        assertEquals(BigDecimal.ZERO, result.discountAmount)
        assertEquals(BigDecimal("4500.00"), result.remainingAmount)
        assertEquals(OrderStatusEnum.PENDING_PAYMENT.value, result.status)
        assertEquals("inst-cover.jpg", result.coverImage)

        verify { orderStatusLogService.logTransition("order-1", "", OrderStatusEnum.PENDING_PAYMENT.value, "user-1", "USER", any()) }
    }

    @Test
    fun `创建订单 - 无机构项目时使用项目参考价`() {
        val request = CreateOrderRequest(projectId = "project-1")

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-2") }

        val result = orderService.createOrder("user-1", request)

        assertEquals(BigDecimal("5000.00"), result.amount)
        assertEquals(BigDecimal.ZERO, result.paidAmount)
        assertEquals("", result.institutionId)
        assertEquals("", result.institutionName)
        assertEquals("project-cover.jpg", result.coverImage)
    }

    @Test
    fun `创建订单 - 有面诊金时正确计算 remainingAmount`() {
        val request = CreateOrderRequest(
            projectId = "project-1",
            institutionProjectId = "inst-proj-1",
            doctorId = "doctor-1"
        )
        val config = DoctorInstitutionProjectConfigEntity(
            doctorId = "doctor-1",
            institutionProjectId = "inst-proj-1",
            consultationFee = BigDecimal("200.00")
        )

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(testInstitutionProject)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
        every { doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns true
        every { doctorRepository.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "测试医生"))
        every { doctorInstitutionProjectConfigRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "inst-proj-1") } returns config
        every { orderRepository.save(any()) } answers { firstArg<OrderEntity>().copy(id = "order-3") }

        val result = orderService.createOrder("user-1", request)

        assertEquals(BigDecimal("200.00"), result.consultationFee)
        // remainingAmount = price - consultationFee = 4500 - 200 = 4300
        assertEquals(BigDecimal("4300.00"), result.remainingAmount)
    }

    @Test
    fun `创建订单 - 项目不存在抛出异常`() {
        val request = CreateOrderRequest(projectId = "non-exist")
        every { projectRepository.findById("non-exist") } returns Optional.empty()

        assertThrows<IllegalArgumentException> {
            orderService.createOrder("user-1", request)
        }
    }

    @Test
    fun `创建订单 - 机构项目封面为空时使用项目封面`() {
        val instProjNoImage = testInstitutionProject.copy(coverImage = "")
        val request = CreateOrderRequest(projectId = "project-1", institutionProjectId = "inst-proj-1")

        every { projectRepository.findById("project-1") } returns Optional.of(testProject)
        every { institutionProjectRepository.findById("inst-proj-1") } returns Optional.of(instProjNoImage)
        every { institutionRepository.findById("inst-1") } returns Optional.of(testInstitution)
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
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        val result = orderService.confirmVerification("o1", "inst-user-1", "123456")

        assertEquals(OrderStatusEnum.VERIFIED.value, result.status)
        verify { orderStatusLogService.logTransition("o1", OrderStatusEnum.CONSULTATION_PAID.value, OrderStatusEnum.VERIFIED.value, "inst-user-1", "INSTITUTION", any()) }
    }

    @Test
    fun `confirmVerification 非法状态转换抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.confirmVerification("o1", "inst-user-1", "123456")
        }
    }

    @Test
    fun `管理侧订单列表不返回用户核销码`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findAll() } returns listOf(order)
        val actor = ManagementActor(
            userId = "admin-1",
            isAdmin = true,
            activeRoles = setOf("ADMIN"),
            doctorId = null,
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )

        val result = orderService.getOrdersForManagement(actor, null, 0, 20)

        assertEquals(1, result.size)
        assertNull(result.single().verifyCode)
    }

    // ---- 确认完成 ----

    @Test
    fun `confirmCompletion 用户确认完成并触发结算`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_COMPLETION.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }
        every { settlementService.saveSettlement("o1") } returns mockk()

        val result = orderService.confirmCompletion("o1", "user-1")

        assertEquals(OrderStatusEnum.COMPLETED.value, result.status)
        // 验证 save 时设置了 settlementAt
        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertNotNull(saved.captured.settlementAt)
        verify { settlementService.saveSettlement("o1") }
    }

    @Test
    fun `confirmCompletion 非本人操作抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_COMPLETION.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.confirmCompletion("o1", "user-2")
        }
    }

    // ---- 取消订单 ----

    @Test
    fun `cancelOrder 用户取消待支付订单 - 保留审计并转为已取消`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
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
    }

    @Test
    fun `cancelOrder 非本人订单抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<RuntimeException> {
            orderService.cancelOrder("o1", "user-2")
        }
    }

    @Test
    fun `cancelOrder 非待支付状态不允许取消`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<RuntimeException> {
            orderService.cancelOrder("o1", "user-1")
        }
    }

    // ---- 超时自动取消 ----

    @Test
    fun `cancelExpiredPendingOrder 取消超时未支付订单`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelExpiredPendingOrder("o1")

        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
    }

    @Test
    fun `cancelExpiredPendingOrder 非 PENDING_PAYMENT 状态跳过`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        orderService.cancelExpiredPendingOrder("o1")

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `cancelBalanceTimeoutOrder 取消尾款超时订单`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.VERIFIED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        orderService.cancelBalanceTimeoutOrder("o1")

        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.CANCELLED.value, saved.captured.status)
    }

    @Test
    fun `cancelBalanceTimeoutOrder 非 VERIFIED 状态跳过`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CONSULTATION_PAID.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        orderService.cancelBalanceTimeoutOrder("o1")

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    // ---- 删除订单 ----

    @Test
    fun `deleteOrder 已取消状态可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CANCELLED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        justRun { orderRepository.deleteById("o1") }

        val result = orderService.deleteOrder("o1", "user-1")

        assertTrue(result)
        verify { orderRepository.deleteById("o1") }
    }

    @Test
    fun `deleteOrder 已完成状态可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.COMPLETED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        justRun { orderRepository.deleteById("o1") }

        assertTrue(orderService.deleteOrder("o1", "user-1"))
    }

    @Test
    fun `deleteOrder 待支付状态不可删除`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.deleteOrder("o1", "user-1")
        }
    }

    @Test
    fun `deleteOrder 非本人订单抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.CANCELLED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.deleteOrder("o1", "user-2")
        }
    }

    @Test
    fun `deleteOrder 不存在的订单返回 false`() {
        every { orderRepository.findById("o1") } returns Optional.empty()

        assertFalse(orderService.deleteOrder("o1", "user-1"))
    }

    // ---- 管理员操作 ----

    @Test
    fun `adminUpdateStatus 合法状态变更`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }

        val result = orderService.adminUpdateStatus("o1", OrderStatusEnum.CONSULTATION_PAID.value)

        assertNotNull(result)
        assertEquals(OrderStatusEnum.CONSULTATION_PAID.value, result?.status)
        verify { orderStatusLogService.logTransition("o1", OrderStatusEnum.PENDING_PAYMENT.value, OrderStatusEnum.CONSULTATION_PAID.value, null, "ADMIN", any()) }
    }

    @Test
    fun `adminUpdateStatus 非法状态变更抛出异常`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        assertThrows<IllegalArgumentException> {
            orderService.adminUpdateStatus("o1", OrderStatusEnum.COMPLETED.value)
        }
    }

    @Test
    fun `autoCompleteReview 超时自动好评并触发结算`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.COMPLETED.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)
        every { orderRepository.save(any()) } answers { firstArg() }
        every { settlementService.saveSettlement("o1") } returns mockk()

        orderService.autoCompleteReview("o1")

        val saved = slot<OrderEntity>()
        verify { orderRepository.save(capture(saved)) }
        assertEquals(OrderStatusEnum.PENDING_SETTLEMENT.value, saved.captured.status)
        assertTrue(saved.captured.hasReview)
        verify { settlementService.saveSettlement("o1") }
    }

    @Test
    fun `autoCompleteReview 不可转换状态跳过`() {
        val order = createTestOrder("o1", "user-1", status = OrderStatusEnum.PENDING_PAYMENT.value)
        every { orderRepository.findById("o1") } returns Optional.of(order)

        orderService.autoCompleteReview("o1")

        verify(exactly = 0) { orderRepository.save(any()) }
    }

    // ---- 辅助方法 ----

    private fun createTestOrder(
        id: String,
        userId: String,
        status: String = OrderStatusEnum.PENDING_PAYMENT.value
    ): OrderEntity = OrderEntity(
        id = id,
        userId = userId,
        projectName = "热玛吉",
        institutionName = "美丽机构",
        coverImage = "cover.jpg",
        price = BigDecimal("4500.00"),
        paidAmount = BigDecimal.ZERO,
        status = status,
        projectId = "project-1",
        institutionId = "inst-1",
        verifyCode = "123456",
        orderNo = "JOY202607311200001234"
    )
}
