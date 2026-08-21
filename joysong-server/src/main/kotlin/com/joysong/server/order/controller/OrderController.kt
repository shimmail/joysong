package com.joysong.server.order.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.common.apiSlice
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.service.PaymentService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.dto.PaymentAttemptResponse
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.refund.service.RefundService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.dto.toConsumerDto
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.review.dto.ReviewResponse
import com.joysong.server.review.service.ReviewService
import org.springframework.security.core.Authentication
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/**
 * 用户端订单控制器
 * 提供订单创建、支付（面诊金/尾款）、核验、完成、退款、评价等接口
 *
 * @author joysong
 * @since 2026-07-30
 */
@RestController
@RequestMapping("/api/orders")
class OrderController(
    private val orderService: OrderService,
    private val paymentService: PaymentService,
    private val refundService: RefundService,
    private val reviewService: ReviewService,
    private val orderStatusLogService: OrderStatusLogService,
    private val settlementRepository: SettlementRepository,
    private val paymentRepository: PaymentRepository
) {

    /** 创建订单 */
    @PostMapping
    fun createOrder(
        authentication: Authentication,
        @RequestBody request: CreateOrderRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val order = orderService.createOrder(userId, request)
            BaseResponse.success(order)
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "创建订单失败")
        }
    }

    /** 获取当前用户的订单列表，可按状态筛选 */
    @GetMapping
    fun getOrders(
        authentication: Authentication,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val orders = if (status.isNullOrBlank()) {
            orderService.getOrdersByUser(userId)
        } else {
            orderService.getOrdersByUserAndStatus(userId, status)
        }
        return BaseResponse.success(orders.map { OrderResponse.from(it) }.apiSlice(offset, limit))
    }

    /** 获取订单详情 */
    @GetMapping("/{id}")
    fun getOrderById(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        val order = orderService.getOrderById(id, userId)
            ?: return BaseResponse<Any>(code = 404, message = "Order not found")
        return BaseResponse.success(OrderResponse.from(order))
    }

    /** Consumer-safe settlement state; ownership is checked before looking up money records. */
    @GetMapping("/{id}/settlement")
    fun getSettlement(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        val order = orderService.getOrderById(id, authentication.principal as String)
            ?: return BaseResponse.error<Any>("Order not found", 404)
        val settlement = settlementRepository.findByOrderId(order.id)
            ?: return BaseResponse.error<Any>("SETTLEMENT_NOT_GENERATED", 409)
        return BaseResponse.success(
            settlement.toConsumerDto(order.settlementAt, paymentRepository.sumSucceededAmountMinor(order.id))
        )
    }

    /** 支付面诊金 */
    @PostMapping("/{id}/pay-consultation")
    fun payConsultationFee(
        @PathVariable id: String,
        authentication: Authentication
    ): Nothing = throw ResponseStatusException(HttpStatus.GONE, "LEGACY_PAYMENT_ENDPOINT_REMOVED")

    /** 支付尾款 */
    @PostMapping("/{id}/pay-balance")
    fun payBalance(
        @PathVariable id: String,
        authentication: Authentication
    ): Nothing = throw ResponseStatusException(HttpStatus.GONE, "LEGACY_PAYMENT_ENDPOINT_REMOVED")

    /** 用户展示给机构的首次到店核销码；此接口不会自行推进订单状态。 */
    @PostMapping("/{id}/verification-code")
    fun requestVerificationCode(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            BaseResponse.success(orderService.requestVerification(id, userId))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "生成核销码失败")
        }
    }

    /**
     * Provider-aware payment entry point used by both mobile clients. Reusing
     * the same Idempotency-Key returns the original attempt.
     */
    @PostMapping("/{id}/payment-attempts")
    fun createPaymentAttempt(
        @PathVariable id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreatePaymentAttemptRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val order = orderService.getOrderById(id, userId)
                ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
            require(order.paymentFlow != "TRAVEL_GROUND_SERVICE_ONLY") {
                "USE_SERVICE_FEE_PAYMENT_ENDPOINT"
            }
            val payment = paymentService.createPaymentSession(
                orderId = id,
                userId = userId,
                paymentType = PaymentType.valueOf(request.paymentType.trim().uppercase()),
                provider = PaymentProvider.parse(request.provider),
                paymentMethod = request.paymentMethod,
                idempotencyKey = idempotencyKey
            )
            BaseResponse.success(PaymentAttemptResponse.from(payment))
        } catch (e: Exception) {
            paymentError(e, "创建支付失败")
        }
    }

    /**
     * Fixed travel-service payment contract. Amount, type, provider and method
     * are intentionally not accepted from the client.
     */
    @PostMapping("/{id}/service-fee-payment-attempts")
    fun createServiceFeePaymentAttempt(
        @PathVariable id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val payment = paymentService.createPaymentSession(
                orderId = id,
                userId = userId,
                paymentType = PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                provider = PaymentProvider.ALIPAY_PLUS,
                paymentMethod = "ALIPAY_PLUS_CASHIER",
                idempotencyKey = idempotencyKey
            )
            BaseResponse.success(PaymentAttemptResponse.from(payment))
        } catch (e: Exception) {
            paymentError(e, "创建支付失败")
        }
    }

    /** 查询订单指定支付阶段的最新一次支付，可选主动向渠道刷新状态。 */
    @GetMapping("/{id}/payments/latest")
    fun getLatestPayment(
        @PathVariable id: String,
        @RequestParam paymentType: String,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val payment = paymentService.getLatestPayment(
                orderId = id,
                userId = userId,
                paymentType = PaymentType.valueOf(paymentType.trim().uppercase()),
                refresh = refresh
            ) ?: return BaseResponse.error<Any>("PAYMENT_NOT_FOUND", 404)
            BaseResponse.success(PaymentAttemptResponse.from(payment))
        } catch (e: Exception) {
            paymentError(e, "查询支付失败")
        }
    }

    /** 兼容旧 Android；语义已调整为仅生成核销码。 */
    @Deprecated("请使用 /verification-code")
    @PostMapping("/{id}/verify")
    fun requestVerificationCodeLegacy(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> = requestVerificationCode(id, authentication)

    /** 用户确认项目完成 */
    @PostMapping("/{id}/confirm-completion")
    fun confirmCompletion(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            BaseResponse.success(orderService.confirmCompletion(id, userId))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "确认完成失败")
        }
    }

    /** 查看订单状态变更日志 */
    @GetMapping("/{id}/status-logs")
    fun getStatusLogs(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        orderService.getOrderById(id, userId)
            ?: return BaseResponse.error<Any>("订单不存在", 404)
        return BaseResponse.success(orderStatusLogService.listByOrderId(id))
    }

    /** 原有支付接口（向后兼容，内部调用面诊金支付） */
    @PostMapping("/{id}/pay")
    fun payOrder(
        @PathVariable id: String,
        @RequestBody request: PayOrderRequest,
        authentication: Authentication
    ): Nothing = throw ResponseStatusException(HttpStatus.GONE, "LEGACY_PAYMENT_ENDPOINT_REMOVED")

    /** 申请退款 */
    @PostMapping("/{id}/refund")
    fun refundOrder(
        @PathVariable id: String,
        @RequestBody request: RefundOrderRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            BaseResponse.success(refundService.applyRefund(
                id, userId, request.reason, request.description, request.evidenceUrl, request.reasonCode
            ))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "退款申请失败")
        }
    }

    /** 提交评价 */
    @PostMapping("/{id}/review")
    fun reviewOrder(
        @PathVariable id: String,
        @RequestBody request: ReviewOrderRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val review = reviewService.submitReview(id, userId, request.rating, request.content, request.tags, request.images)
            BaseResponse.success(ReviewResponse.from(review))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "评价提交失败")
        }
    }

    /** 查询退款详情 */
    @GetMapping("/{id}/refund")
    fun getRefundDetail(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        val refund = refundService.getRefundByOrderId(id, userId)
            ?: return BaseResponse.error<Any>("未找到退款记录", 404)
        return BaseResponse.success(refund)
    }

    /** 取消退款申请 */
    @PostMapping("/{id}/cancel-refund")
    fun cancelRefund(@PathVariable id: String, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            BaseResponse.success(refundService.cancelRefund(id, userId))
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "取消退款失败")
        }
    }

    /** 取消待支付订单（物理删除） */
    @PostMapping("/{id}/cancel")
    fun cancelOrder(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            orderService.cancelOrder(id, userId)
            BaseResponse.success("订单已取消")
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "取消订单失败")
        }
    }

    /** 删除订单（软删除），仅允许删除已结束状态的订单 */
    @DeleteMapping("/{id}")
    fun deleteOrder(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return try {
            val success = orderService.deleteOrder(id, userId)
            if (success) {
                BaseResponse.success("订单已删除")
            } else {
                BaseResponse.error<Any>("订单不存在", 404)
            }
        } catch (e: Exception) {
            BaseResponse.error<Any>(e.message ?: "删除订单失败")
        }
    }

    private fun paymentError(error: Exception, fallback: String): BaseResponse<Any> {
        val message = error.message ?: fallback
        val code = if (error is PaymentProviderException || message == "PAYMENT_PROVIDER_UNAVAILABLE") 503 else 400
        return BaseResponse.error(message, code)
    }
}

data class PayOrderRequest(val method: String)
data class CreatePaymentAttemptRequest(
    val paymentType: String,
    val provider: String,
    val paymentMethod: String
)
data class RefundOrderRequest(
    val reason: String,
    val description: String = "",
    val evidenceUrl: String = "",
    val reasonCode: String? = null
)
data class ReviewOrderRequest(val rating: Int, val content: String, val tags: String = "", val images: String = "")
