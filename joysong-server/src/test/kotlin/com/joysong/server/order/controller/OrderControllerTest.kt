package com.joysong.server.order.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.service.PaymentService
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.service.RefundService
import com.joysong.server.review.service.ReviewService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.wallet.dto.ConsumerSettlementDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import java.math.BigDecimal
import java.time.LocalDateTime

class OrderControllerTest {
    @Test
    fun `create order request JSON omits legacy quantity and coupon fields`() {
        val json = jacksonObjectMapper().readTree(
            jacksonObjectMapper().writeValueAsString(
                CreateOrderRequest("project-1", "ip-1", "doctor-1", "consultant-1")
            )
        )

        assertEquals("consultant-1", json["consultantId"].asText())
        assertFalse(json.has("quantity"))
        assertFalse(json.has("userCouponId"))
    }

    @Test
    fun `pending service fee hides consultant and institution fulfillment details`() {
        val response = OrderResponse.from(serviceOrder(status = "PENDING_SERVICE_FEE"))

        assertTrue(response.consultantBound)
        assertFalse(response.serviceActivated)
        assertFalse(response.consultantDetailsVisible)
        assertFalse(response.serviceConversationReadable)
        assertFalse(response.serviceMessagingEnabled)
        assertNull(response.consultantId)
        assertNull(response.consultantName)
        assertNull(response.consultantAvatar)
        assertNull(response.institutionId)
        assertNull(response.institutionName)
    }

    @Test
    fun `active service exposes public consultant snapshot and enables messaging`() {
        val response = OrderResponse.from(
            serviceOrder(status = "SERVICE_ACTIVE", serviceActivatedAt = LocalDateTime.now())
        )

        assertEquals("consultant-1", response.consultantId)
        assertEquals("测试咨询师", response.consultantName)
        assertEquals("consultant.png", response.consultantAvatar)
        assertEquals("inst-1", response.institutionId)
        assertEquals("美丽机构", response.institutionName)
        assertTrue(response.serviceActivated)
        assertTrue(response.consultantDetailsVisible)
        assertTrue(response.serviceConversationReadable)
        assertTrue(response.serviceMessagingEnabled)
    }

    @Test
    fun `refund states split detail history and messaging entitlements`() {
        listOf("REFUND_REVIEW", "REFUND_PROCESSING").forEach { status ->
            val response = OrderResponse.from(serviceOrder(status, LocalDateTime.now()))
            assertTrue(response.consultantDetailsVisible, status)
            assertTrue(response.serviceConversationReadable, status)
            assertFalse(response.serviceMessagingEnabled, status)
            assertEquals("consultant-1", response.consultantId, status)
        }

        val refunded = OrderResponse.from(serviceOrder("REFUNDED", LocalDateTime.now()))
        assertFalse(refunded.consultantDetailsVisible)
        assertTrue(refunded.serviceConversationReadable)
        assertFalse(refunded.serviceMessagingEnabled)
        assertNull(refunded.consultantId)
        assertNull(refunded.institutionId)
    }

    @Test
    fun `unactivated refunded travel order cannot read conversation`() {
        val response = OrderResponse.from(serviceOrder(status = "REFUNDED", serviceActivatedAt = null))

        assertFalse(response.serviceActivated)
        assertFalse(response.consultantDetailsVisible)
        assertFalse(response.serviceConversationReadable)
        assertFalse(response.serviceMessagingEnabled)
        assertNull(response.consultantId)
        assertNull(response.institutionId)
    }

    @Test
    fun `legacy order cannot gain travel service entitlements from anomalous status and timestamp`() {
        val response = OrderResponse.from(
            serviceOrder(
                status = "SERVICE_ACTIVE",
                serviceActivatedAt = LocalDateTime.now(),
                paymentFlow = "LEGACY_MEDICAL"
            )
        )

        assertFalse(response.consultantBound)
        assertFalse(response.serviceActivated)
        assertFalse(response.consultantDetailsVisible)
        assertFalse(response.serviceConversationReadable)
        assertFalse(response.serviceMessagingEnabled)
        assertNull(response.consultantId)
        assertNull(response.institutionId)
    }

    @Test
    fun `management projection retains internal consultant snapshot before activation`() {
        val response = OrderResponse.forManagement(serviceOrder(status = "PENDING_SERVICE_FEE"))

        assertEquals("consultant-1", response.consultantId)
        assertEquals("测试咨询师", response.consultantName)
        assertEquals("consultant.png", response.consultantAvatar)
        assertEquals("inst-1", response.institutionId)
        assertEquals("美丽机构", response.institutionName)
    }

    @Test
    fun `another consumer gets not found before settlement lookup`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { authentication.principal } returns "other-user"
        every { orders.getOrderById("order-1", "other-user") } returns null
        val controller = controller(orders, settlements)

        val response = controller.getSettlement("order-1", authentication)

        assertEquals(404, response.code)
        verify(exactly = 0) { settlements.findByOrderId(any()) }
    }

    @Test
    fun `owned order without settlement has distinct not generated response`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns OrderEntity(
            id = "order-1", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "COMPLETED"
        )
        every { settlements.findByOrderId("order-1") } returns null
        val controller = controller(orders, settlements)

        val response = controller.getSettlement("order-1", authentication)

        assertEquals(409, response.code)
        assertEquals("SETTLEMENT_NOT_GENERATED", response.message)
    }

    @Test
    fun `owned settlement reports gross successful payments separately from net after refunds`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val payments = mockk<PaymentRepository>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns OrderEntity(
            id = "order-1", userId = "user-1", projectName = "项目", price = BigDecimal.TEN, status = "COMPLETED"
        )
        every { settlements.findByOrderId("order-1") } returns SettlementEntity(
            id = 5, orderId = "order-1", currency = "USD", totalAmountMinor = 800, totalAmount = BigDecimal("8.00")
        )
        every { payments.sumSucceededAmountMinor("order-1") } returns 1000
        val controller = controller(orders, settlements, payments)

        val response = controller.getSettlement("order-1", authentication)
        val data = response.data as ConsumerSettlementDto

        assertEquals(1000, data.grossTotalPaid.minor)
        assertEquals(800, data.netSettled.minor)
        assertEquals("USD", data.grossTotalPaid.currency)
    }

    private fun controller(
        orders: OrderService,
        settlements: SettlementRepository,
        payments: PaymentRepository = mockk()
    ) = OrderController(
        orders, mockk<PaymentService>(), mockk<RefundService>(), mockk<ReviewService>(), mockk<OrderStatusLogService>(), settlements, payments
    )

    private fun serviceOrder(
        status: String,
        serviceActivatedAt: LocalDateTime? = null,
        paymentFlow: String = "TRAVEL_GROUND_SERVICE_ONLY"
    ) = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        institutionId = "inst-1",
        institutionName = "美丽机构",
        consultantId = "consultant-1",
        consultantName = "测试咨询师",
        consultantAvatar = "consultant.png",
        price = BigDecimal("400.00"),
        totalAmountMinor = 40_000,
        paymentFlow = paymentFlow,
        medicalListPriceMinor = 100_000,
        platformServiceRateBps = 4_000,
        travelGroundServiceFeeMinor = 40_000,
        status = status,
        serviceActivatedAt = serviceActivatedAt
    )
}
