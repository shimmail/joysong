package com.joysong.server.payment.service

import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentNextAction
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.ProviderConfirmPaymentRequest
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.repository.PaymentRepository
import org.springframework.stereotype.Service

data class PaymentSessionResult(
    val payment: PaymentEntity,
    val nextAction: PaymentNextAction? = null
)

/**
 * Unified payment orchestrator.
 *
 * Provider network calls deliberately happen outside database transactions.
 * PaymentPersistenceService owns the short transactions before and after them.
 */
@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val orderStatusLogService: OrderStatusLogService,
    private val paymentGatewayRegistry: PaymentGatewayRegistry = PaymentGatewayRegistry(emptyList()),
    private val paymentPersistenceService: PaymentPersistenceService = PaymentPersistenceService(
        paymentRepository,
        orderRepository,
        orderStatusLogService
    )
) {
    companion object {
        private val terminalStatuses = PaymentStatus.terminalDatabaseValues
    }

    fun createPaymentSession(
        orderId: String,
        userId: String,
        paymentType: PaymentType,
        provider: PaymentProvider,
        paymentMethod: String,
        idempotencyKey: String
    ): PaymentSessionResult {
        val normalizedKey = normalizeIdempotencyKey(idempotencyKey)
        val normalizedMethod = paymentMethod.trim().uppercase()
        require(normalizedMethod.isNotBlank() && normalizedMethod.length <= 50) { "INVALID_PAYMENT_METHOD" }

        // Reject disabled/unimplemented providers before creating a local attempt.
        val gateway = paymentGatewayRegistry.require(provider)
        val prepared = paymentPersistenceService.prepareAttempt(
            orderId = orderId,
            userId = userId,
            paymentType = paymentType,
            provider = provider,
            paymentMethod = normalizedMethod,
            idempotencyKey = normalizedKey
        )
        if (prepared.status in terminalStatuses) return PaymentSessionResult(prepared)

        return try {
            val result = if (prepared.providerPaymentId.isNullOrBlank()) {
                gateway.createPayment(
                    ProviderCreatePaymentRequest(
                        paymentId = prepared.id,
                        orderId = prepared.orderId,
                        amountMinor = requireNotNull(prepared.amountMinor) { "PAYMENT_AMOUNT_MISSING" },
                        currency = prepared.currency,
                        paymentMethod = normalizedMethod,
                        idempotencyKey = providerCreateIdempotencyKey(prepared.id)
                    )
                )
            } else {
                gateway.queryPayment(prepared.providerPaymentId)
            }
            PaymentSessionResult(
                payment = paymentPersistenceService.applyProviderResult(prepared.id, result),
                nextAction = result.nextAction
            )
        } catch (error: PaymentProviderException) {
            paymentPersistenceService.markProviderError(
                paymentId = prepared.id,
                status = if (error.outcomeUnknown) PaymentStatus.PROCESSING else PaymentStatus.FAILED,
                failureCode = error.errorCode,
                failureMessage = error.message
            )
            throw error
        } catch (error: Exception) {
            paymentPersistenceService.markProviderError(
                paymentId = prepared.id,
                status = PaymentStatus.PROCESSING,
                failureCode = "PROVIDER_REQUEST_UNCERTAIN",
                failureMessage = error.message
            )
            throw error
        }
    }

    /** Compatibility entry point for existing server callers. */
    fun createPaymentAttempt(
        orderId: String,
        userId: String,
        paymentType: PaymentType,
        provider: PaymentProvider,
        paymentMethod: String,
        idempotencyKey: String
    ): PaymentEntity = createPaymentSession(
        orderId,
        userId,
        paymentType,
        provider,
        paymentMethod,
        idempotencyKey
    ).payment

    fun payConsultationFee(orderId: String, userId: String): PaymentEntity {
        throw IllegalStateException("LEGACY_PAYMENT_ENDPOINT_REMOVED")
    }

    fun payBalance(orderId: String, userId: String): PaymentEntity {
        throw IllegalStateException("LEGACY_PAYMENT_ENDPOINT_REMOVED")
    }

    fun getPayment(paymentId: String, userId: String, refresh: Boolean = false): PaymentSessionResult {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("PAYMENT_NOT_FOUND") }
        require(payment.userId == userId) { "PAYMENT_ACCESS_DENIED" }
        return if (refresh) refreshPayment(payment) else PaymentSessionResult(payment)
    }

    fun getLatestPayment(
        orderId: String,
        userId: String,
        paymentType: PaymentType,
        refresh: Boolean = false
    ): PaymentSessionResult? {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("ORDER_NOT_FOUND") }
        require(order.userId == userId) { "ORDER_ACCESS_DENIED" }
        val payment = paymentRepository.findFirstByOrderIdAndPaymentTypeOrderByCreatedAtDesc(
            orderId,
            paymentType.name
        ) ?: return null
        return if (refresh) refreshPayment(payment) else PaymentSessionResult(payment)
    }

    fun confirmPayment(
        paymentId: String,
        userId: String,
        idempotencyKey: String
    ): PaymentSessionResult {
        normalizeIdempotencyKey(idempotencyKey)
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("PAYMENT_NOT_FOUND") }
        require(payment.userId == userId) { "PAYMENT_ACCESS_DENIED" }
        if (payment.status in terminalStatuses) return PaymentSessionResult(payment)
        val providerPaymentId = requireNotNull(payment.providerPaymentId) { "PROVIDER_PAYMENT_ID_MISSING" }
        val provider = PaymentProvider.parse(payment.provider)
        val result = paymentGatewayRegistry.require(provider).confirmPayment(
            ProviderConfirmPaymentRequest(
                paymentId = payment.id,
                providerPaymentId = providerPaymentId,
                idempotencyKey = "payment-confirm-${payment.id}"
            )
        )
        return PaymentSessionResult(
            paymentPersistenceService.applyProviderResult(payment.id, result),
            result.nextAction
        )
    }

    /** Applies an asynchronously verified provider event through the same state transition path. */
    fun handleProviderPaymentEvent(
        provider: PaymentProvider,
        providerPaymentId: String,
        providerTransactionId: String?,
        status: PaymentStatus,
        amountMinor: Long? = null,
        currency: String? = null,
        failureCode: String? = null,
        failureMessage: String? = null,
        localPaymentId: String? = null
    ): PaymentEntity {
        val payment = paymentRepository.findByProviderAndProviderPaymentId(provider.name, providerPaymentId)
            ?: localPaymentId?.let { id -> paymentRepository.findById(id).orElse(null) }
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        require(payment.provider == provider.name) { "PAYMENT_PROVIDER_MISMATCH" }
        require(status == PaymentStatus.SUCCEEDED || status in setOf(
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.EXPIRED
        )) { "UNSUPPORTED_PROVIDER_PAYMENT_STATUS" }
        return paymentPersistenceService.applyProviderResult(
            payment.id,
            ProviderPaymentResult(
                status = status,
                providerPaymentId = providerPaymentId,
                providerTransactionId = providerTransactionId,
                amountMinor = amountMinor,
                currency = currency,
                failureCode = failureCode,
                failureMessage = failureMessage
            )
        )
    }

    /** Internal entry point for reconciliation jobs; no user authorization is required. */
    fun reconcilePayment(paymentId: String): PaymentEntity {
        val payment = paymentRepository.findById(paymentId)
            .orElseThrow { IllegalArgumentException("PAYMENT_NOT_FOUND") }
        if (payment.status in terminalStatuses) return payment
        val gateway = paymentGatewayRegistry.require(PaymentProvider.parse(payment.provider))
        val result = if (payment.providerPaymentId.isNullOrBlank()) {
            gateway.createPayment(
                ProviderCreatePaymentRequest(
                    paymentId = payment.id,
                    orderId = payment.orderId,
                    amountMinor = requireNotNull(payment.amountMinor) { "PAYMENT_AMOUNT_MISSING" },
                    currency = payment.currency,
                    paymentMethod = requireNotNull(payment.paymentMethod) { "PAYMENT_METHOD_MISSING" },
                    idempotencyKey = providerCreateIdempotencyKey(payment.id)
                )
            )
        } else {
            gateway.queryPayment(payment.providerPaymentId)
        }
        return paymentPersistenceService.applyProviderResult(payment.id, result)
    }

    private fun refreshPayment(payment: PaymentEntity): PaymentSessionResult {
        if (payment.status in terminalStatuses) return PaymentSessionResult(payment)
        val providerPaymentId = payment.providerPaymentId ?: return PaymentSessionResult(payment)
        val gateway = paymentGatewayRegistry.require(PaymentProvider.parse(payment.provider))
        val result = gateway.queryPayment(providerPaymentId)
        return PaymentSessionResult(
            paymentPersistenceService.applyProviderResult(payment.id, result),
            result.nextAction
        )
    }

    @Deprecated("请使用 payConsultationFee 或 payBalance 进行分阶段支付")
    fun processPayment(orderId: String, userId: String, method: String): PaymentEntity =
        payConsultationFee(orderId, userId)

    fun adminListAll(): List<PaymentEntity> = paymentRepository.findAll()

    private fun normalizeIdempotencyKey(value: String): String = value.trim().also {
        require(it.length in 8..100) { "INVALID_IDEMPOTENCY_KEY" }
    }

    private fun providerCreateIdempotencyKey(paymentId: String) = "payment-create-$paymentId"

}
