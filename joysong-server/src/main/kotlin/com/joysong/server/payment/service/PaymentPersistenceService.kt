package com.joysong.server.payment.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.Money
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.repository.PaymentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.UUID

@Service
class PaymentPersistenceService(
    private val paymentRepository: PaymentRepository,
    private val orderRepository: OrderRepository,
    private val orderStatusLogService: OrderStatusLogService
) {
    private val secureRandom = SecureRandom()

    companion object {
        private val log = LoggerFactory.getLogger(PaymentPersistenceService::class.java)
        private const val METHOD_ONLINE = "ONLINE"
    }

    /** Short transaction: validates the order and persists a local CREATED attempt. */
    @Transactional(rollbackFor = [Exception::class])
    fun prepareAttempt(
        orderId: String,
        userId: String,
        paymentType: PaymentType,
        provider: PaymentProvider,
        paymentMethod: String,
        idempotencyKey: String
    ): PaymentEntity {
        paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let { existing ->
            require(existing.orderId == orderId &&
                existing.paymentType == paymentType.name &&
                existing.provider == provider.name
            ) { "IDEMPOTENCY_KEY_CONFLICT" }
            return existing
        }

        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        require(order.userId == userId) { "无权操作该订单" }

        findSuccessfulPayment(orderId, paymentType)?.let { return it }
        paymentRepository.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
            orderId,
            paymentType.name,
            listOf(
                PaymentStatus.CREATED.name,
                PaymentStatus.REQUIRES_ACTION.name,
                PaymentStatus.PROCESSING.name
            )
        )?.let { active ->
            require(active.provider == provider.name && active.paymentMethod == paymentMethod) {
                "PAYMENT_ATTEMPT_IN_PROGRESS"
            }
            return active
        }
        validateOrderStage(order, paymentType)

        val currency = Money.normalizeCurrency(order.currency)
        val amountMinor = when (paymentType) {
            PaymentType.CONSULTATION_FEE -> order.consultationFeeMinor
                ?: Money.toMinor(order.consultationFee, currency)
            PaymentType.BALANCE -> order.remainingAmountMinor
                ?: Money.toMinor(order.remainingAmount, currency)
        }
        require(amountMinor > 0) { "PAYMENT_AMOUNT_NOT_POSITIVE" }
        val now = LocalDateTime.now()
        return paymentRepository.saveAndFlush(
            PaymentEntity(
                id = UUID.randomUUID().toString(),
                orderId = orderId,
                userId = userId,
                amount = Money.fromMinor(amountMinor, currency),
                method = METHOD_ONLINE,
                status = PaymentStatus.CREATED.name,
                transactionId = "",
                paymentType = paymentType.name,
                provider = provider.name,
                paymentMethod = paymentMethod,
                currency = currency,
                amountMinor = amountMinor,
                idempotencyKey = idempotencyKey,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** Short transaction: persists a provider result and atomically advances the order on success. */
    @Transactional(rollbackFor = [Exception::class])
    fun applyProviderResult(paymentId: String, result: ProviderPaymentResult): PaymentEntity {
        val payment = paymentRepository.findByIdForUpdate(paymentId)
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        if (payment.status == PaymentStatus.REFUNDED.name) return payment
        require(result.providerPaymentId.isNotBlank()) { "PROVIDER_PAYMENT_ID_MISSING" }
        require(result.status in setOf(
            PaymentStatus.CREATED,
            PaymentStatus.REQUIRES_ACTION,
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED,
            PaymentStatus.CANCELLED,
            PaymentStatus.EXPIRED
        )) { "UNSUPPORTED_PROVIDER_PAYMENT_STATUS" }
        require(payment.providerPaymentId == null || payment.providerPaymentId == result.providerPaymentId) {
            "PROVIDER_PAYMENT_ID_CONFLICT"
        }
        result.amountMinor?.let { require(it == payment.amountMinor) { "PAYMENT_AMOUNT_MISMATCH" } }
        result.currency?.let {
            require(Money.normalizeCurrency(it) == payment.currency) { "PAYMENT_CURRENCY_MISMATCH" }
        }

        if (payment.paidAt != null && payment.status in PaymentStatus.successfulDatabaseValues) {
            return payment
        }
        if (payment.status in setOf(
                PaymentStatus.FAILED.name,
                PaymentStatus.CANCELLED.name,
                PaymentStatus.EXPIRED.name
            ) && result.status != PaymentStatus.SUCCEEDED && result.status.name != payment.status
        ) {
            return payment
        }

        val now = LocalDateTime.now()
        var updated = paymentRepository.save(
            payment.copy(
                status = result.status.name,
                providerPaymentId = result.providerPaymentId,
                providerTransactionId = result.providerTransactionId ?: payment.providerTransactionId,
                transactionId = result.providerTransactionId
                    ?: payment.transactionId.takeIf { it.isNotBlank() }
                    ?: result.providerPaymentId,
                failureCode = result.failureCode,
                failureMessage = result.failureMessage?.take(500),
                authorizedAt = if (result.status == PaymentStatus.PROCESSING) {
                    payment.authorizedAt ?: now
                } else payment.authorizedAt,
                cancelledAt = if (result.status == PaymentStatus.CANCELLED) now else payment.cancelledAt,
                expiresAt = result.expiresAt ?: payment.expiresAt,
                updatedAt = now
            )
        )
        if (result.status == PaymentStatus.SUCCEEDED) {
            val order = orderRepository.findByIdForUpdate(payment.orderId)
                ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
            updated = completeSuccessfulPayment(updated, order, now)
        }
        return updated
    }

    @Transactional(rollbackFor = [Exception::class])
    fun markProviderError(
        paymentId: String,
        status: PaymentStatus,
        failureCode: String,
        failureMessage: String?
    ): PaymentEntity {
        require(status == PaymentStatus.FAILED || status == PaymentStatus.PROCESSING) {
            "INVALID_PROVIDER_ERROR_STATUS"
        }
        val payment = paymentRepository.findByIdForUpdate(paymentId)
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        if (payment.status in PaymentStatus.successfulDatabaseValues || payment.status == PaymentStatus.REFUNDED.name) {
            return payment
        }
        return paymentRepository.save(
            payment.copy(
                status = status.name,
                failureCode = failureCode.take(100),
                failureMessage = failureMessage?.take(500),
                updatedAt = LocalDateTime.now()
            )
        )
    }

    private fun completeSuccessfulPayment(
        payment: PaymentEntity,
        order: OrderEntity,
        now: LocalDateTime
    ): PaymentEntity {
        val type = PaymentType.valueOf(payment.paymentType)
        validateOrderStage(order, type)
        val fromStatus = order.status
        val updatedOrder = when (type) {
            PaymentType.CONSULTATION_FEE -> order.copy(
                status = OrderStatusEnum.CONSULTATION_PAID.value,
                paymentTime = now,
                paidAmount = payment.amount,
                paidAmountMinor = payment.amountMinor,
                updatedAt = now
            )
            PaymentType.BALANCE -> order.copy(
                status = OrderStatusEnum.BALANCE_PAID.value,
                balancePaidAt = now,
                paidAmount = order.price,
                paidAmountMinor = order.totalAmountMinor ?: Money.toMinor(order.price, order.currency),
                verifyCode = (secureRandom.nextInt(900000) + 100000).toString(),
                verifiedAt = null,
                updatedAt = now
            )
        }
        orderRepository.save(updatedOrder)
        orderStatusLogService.logTransition(
            orderId = order.id,
            fromStatus = fromStatus,
            toStatus = updatedOrder.status,
            operatorId = payment.userId,
            operatorType = "USER",
            remark = if (type == PaymentType.CONSULTATION_FEE) "支付面诊金" else "支付尾款，生成二次核销码"
        )
        val completed = paymentRepository.save(
            payment.copy(
                status = PaymentStatus.SUCCEEDED.name,
                paidAt = now,
                failureCode = null,
                failureMessage = null,
                updatedAt = now
            )
        )
        log.info("订单[{}]支付成功，阶段={}, provider={}, paymentId={}", order.orderNo, type, payment.provider, payment.id)
        return completed
    }

    private fun validateOrderStage(order: OrderEntity, paymentType: PaymentType) {
        val current = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        val target = when (paymentType) {
            PaymentType.CONSULTATION_FEE -> OrderStatusEnum.CONSULTATION_PAID
            PaymentType.BALANCE -> OrderStatusEnum.BALANCE_PAID
        }
        require(current.canTransitionTo(target)) {
            if (paymentType == PaymentType.BALANCE) "当前状态[${current.value}]不允许支付尾款，需先完成到店核验"
            else "当前状态[${current.value}]不允许支付面诊金"
        }
    }

    private fun findSuccessfulPayment(orderId: String, type: PaymentType): PaymentEntity? =
        paymentRepository.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
            orderId,
            type.name,
            PaymentStatus.successfulDatabaseValues
        )
}
