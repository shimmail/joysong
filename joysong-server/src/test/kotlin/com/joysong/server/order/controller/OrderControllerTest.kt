package com.joysong.server.order.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.order.application.DevelopmentOrderAutoPaymentResult
import com.joysong.server.order.application.DevelopmentOrderAutoPaymentService
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.service.PaymentService
import com.joysong.server.payment.service.PaymentSessionResult
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.service.RefundService
import com.joysong.server.review.service.ReviewService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.wallet.dto.ConsumerSettlementDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDateTime

class OrderControllerTest {
    @Test
    fun `enabled development auto payment re-reads the activated order after creation`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val autoPayment = mockk<DevelopmentOrderAutoPaymentService>()
        val created = OrderResponse.from(serviceOrder("PENDING_SERVICE_FEE"))
        val activated = serviceOrder("SERVICE_ACTIVE", LocalDateTime.now())
        every { authentication.principal } returns "user-1"
        every { orders.createOrder("user-1", any()) } returns created
        every { autoPayment.attempt("order-1", "user-1") } returns DevelopmentOrderAutoPaymentResult(
            successful = true,
            payment = serviceFeePayment().copy(status = PaymentStatus.SUCCEEDED.name)
        )
        every { orders.getOrderById("order-1", "user-1") } returns activated

        val response = controller(
            orders,
            settlements,
            developmentAutoPaymentService = autoPayment
        ).createOrder(
            authentication,
            CreateOrderRequest("project-1", "ip-1", "doctor-1", "consultant-1")
        )

        assertEquals(200, response.code)
        assertEquals("SERVICE_ACTIVE", (response.data as OrderResponse).status)
        verifyOrder {
            orders.createOrder("user-1", any())
            autoPayment.attempt("order-1", "user-1")
            orders.getOrderById("order-1", "user-1")
        }
    }

    @Test
    fun `failed development auto payment still re-reads and returns the pending order`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val autoPayment = mockk<DevelopmentOrderAutoPaymentService>()
        val pending = serviceOrder("PENDING_SERVICE_FEE")
        every { authentication.principal } returns "user-1"
        every { orders.createOrder("user-1", any()) } returns OrderResponse.from(pending)
        every { autoPayment.attempt("order-1", "user-1") } returns DevelopmentOrderAutoPaymentResult(
            successful = false,
            failureCode = "PROVIDER_TIMEOUT",
            outcomeUnknown = true
        )
        every { orders.getOrderById("order-1", "user-1") } returns pending

        val response = controller(
            orders,
            settlements,
            developmentAutoPaymentService = autoPayment
        ).createOrder(
            authentication,
            CreateOrderRequest("project-1", "ip-1", "doctor-1", "consultant-1")
        )

        assertEquals(200, response.code)
        assertEquals("PENDING_SERVICE_FEE", (response.data as OrderResponse).status)
        verify(exactly = 1) { autoPayment.attempt("order-1", "user-1") }
        verify(exactly = 1) { orders.getOrderById("order-1", "user-1") }
    }

    @Test
    fun `order creation keeps the original response when development auto payment bean is absent`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val created = OrderResponse.from(serviceOrder("PENDING_SERVICE_FEE"))
        every { authentication.principal } returns "user-1"
        every { orders.createOrder("user-1", any()) } returns created

        val response = controller(orders, settlements).createOrder(
            authentication,
            CreateOrderRequest("project-1", "ip-1", "doctor-1", "consultant-1")
        )

        assertSame(created, response.data)
        verify(exactly = 0) { orders.getOrderById(any(), any()) }
    }

    @Test
    fun `unavailable service fee provider returns HTTP 503 with matching error body`() {
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val paymentService = mockk<PaymentService>()
        every {
            paymentService.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "idem-key-123"
            )
        } throws PaymentProviderException(
            "PAYMENT_PROVIDER_UNAVAILABLE",
            retryable = false,
            outcomeUnknown = false
        )
        val mvc = MockMvcBuilders
            .standaloneSetup(controller(orders, settlements, paymentService = paymentService))
            .build()

        mvc.perform(
            post("/api/orders/order-1/service-fee-payment-attempts")
                .header("Idempotency-Key", "idem-key-123")
                .principal(TestingAuthenticationToken("user-1", null))
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value(503))
            .andExpect(jsonPath("$.message").value("PAYMENT_PROVIDER_UNAVAILABLE"))
    }

    @Test
    fun `invalid generic payment request returns HTTP 400 with matching error body`() {
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { orders.getOrderById("order-1", "user-1") } returns serviceOrder("PENDING_SERVICE_FEE")
        val mvc = MockMvcBuilders
            .standaloneSetup(controller(orders, settlements))
            .build()

        mvc.perform(
            post("/api/orders/order-1/payment-attempts")
                .header("Idempotency-Key", "idem-key-123")
                .principal(TestingAuthenticationToken("user-1", null))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"paymentType":"BALANCE","provider":"ALIPAY_PLUS","paymentMethod":"ALIPAY_PLUS_CASHIER"}"""
                )
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("MEDICAL_PAYMENT_NOT_SUPPORTED"))
    }

    @Test
    fun `service fee endpoint fixes payment contract without request body`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val paymentService = mockk<PaymentService>()
        val payment = serviceFeePayment()
        every { authentication.principal } returns "user-1"
        every {
            paymentService.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "idem-key-123"
            )
        } returns PaymentSessionResult(payment)

        val response = controller(orders, settlements, paymentService = paymentService)
            .createServiceFeePaymentAttempt("order-1", "idem-key-123", authentication)

        assertEquals(200, response.statusCode.value())
        assertEquals(200, response.body?.code)
        verify(exactly = 1) {
            paymentService.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "idem-key-123"
            )
        }
    }

    @Test
    fun `generic payment attempt endpoint rejects travel service orders`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val paymentService = mockk<PaymentService>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns serviceOrder("PENDING_SERVICE_FEE")

        val response = controller(orders, settlements, paymentService = paymentService).createPaymentAttempt(
            "order-1",
            "idem-key-123",
            CreatePaymentAttemptRequest(
                PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                PaymentProvider.ALIPAY_PLUS.name,
                "ALIPAY_PLUS_CASHIER"
            ),
            authentication
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(400, response.body?.code)
        assertEquals("USE_SERVICE_FEE_PAYMENT_ENDPOINT", response.body?.message)
        verify(exactly = 0) { paymentService.createPaymentSession(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `generic payment endpoint explicitly rejects medical type for travel service order`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        val paymentService = mockk<PaymentService>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns serviceOrder("PENDING_SERVICE_FEE")

        val response = controller(orders, settlements, paymentService = paymentService).createPaymentAttempt(
            "order-1",
            "idem-key-123",
            CreatePaymentAttemptRequest(
                PaymentType.BALANCE.name,
                PaymentProvider.ALIPAY_PLUS.name,
                "ALIPAY_PLUS_CASHIER"
            ),
            authentication
        )

        assertEquals(400, response.statusCode.value())
        assertEquals(400, response.body?.code)
        assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", response.body?.message)
        verify(exactly = 0) { paymentService.createPaymentSession(any(), any(), any(), any(), any(), any()) }
    }

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
    fun `legacy order retains fulfillment snapshots without gaining travel service entitlements`() {
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
        assertEquals("consultant-1", response.consultantId)
        assertEquals("测试咨询师", response.consultantName)
        assertEquals("consultant.png", response.consultantAvatar)
        assertEquals("inst-1", response.institutionId)
        assertEquals("美丽机构", response.institutionName)
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
    fun `travel service order explicitly rejects settlement lookup`() {
        val authentication = mockk<Authentication>()
        val orders = mockk<OrderService>()
        val settlements = mockk<SettlementRepository>()
        every { authentication.principal } returns "user-1"
        every { orders.getOrderById("order-1", "user-1") } returns
            serviceOrder("SERVICE_ACTIVE", LocalDateTime.now())

        val response = controller(orders, settlements).getSettlement("order-1", authentication)

        assertEquals(400, response.code)
        assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", response.message)
        verify(exactly = 0) { settlements.findByOrderId(any()) }
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
        payments: PaymentRepository = mockk(),
        paymentService: PaymentService = mockk(),
        developmentAutoPaymentService: DevelopmentOrderAutoPaymentService? = null
    ): OrderController {
        val provider = mockk<ObjectProvider<DevelopmentOrderAutoPaymentService>>()
        every { provider.getIfAvailable() } returns developmentAutoPaymentService
        return OrderController(
            orders,
            paymentService,
            mockk<RefundService>(),
            mockk<ReviewService>(),
            mockk<OrderStatusLogService>(),
            settlements,
            payments,
            provider
        )
    }

    private fun serviceFeePayment() = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("400.00"),
        method = "ONLINE",
        status = PaymentStatus.CREATED.name,
        paymentType = "TRAVEL_GROUND_SERVICE_FEE",
        provider = "ALIPAY_PLUS",
        paymentMethod = "ALIPAY_PLUS_CASHIER",
        currency = "USD",
        amountMinor = 40_000
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
