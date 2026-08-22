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
        private const val TRAVEL_SERVICE_PAYMENT_FLOW = "TRAVEL_GROUND_SERVICE_ONLY"
        private const val TRAVEL_SERVICE_CURRENCY = "USD"
        private const val TRAVEL_SERVICE_PAYMENT_METHOD = "ALIPAY_PLUS_CASHIER"
        private const val PAYMENT_ATTEMPT_TIMEOUT_MINUTES = 30L
        private const val MEDICAL_PAYMENT_NOT_SUPPORTED = "MEDICAL_PAYMENT_NOT_SUPPORTED"
        private const val DUPLICATE_PAYMENT_SUCCEEDED = "DUPLICATE_PAYMENT_SUCCEEDED"
        private const val PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE = "PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE"
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
        val existingByIdempotencyKey = paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
        existingByIdempotencyKey?.let { existing ->
            require(existing.orderId == orderId &&
                existing.paymentType == paymentType.name &&
                existing.provider == provider.name &&
                existing.paymentMethod == paymentMethod
            ) { "IDEMPOTENCY_KEY_CONFLICT" }
            if (existing.status in PaymentStatus.terminalDatabaseValues) return existing
        }

        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        require(order.userId == userId) { "无权操作该订单" }
        validatePaymentContract(order, paymentType, provider, paymentMethod)
        validateOrderStage(order, paymentType)

        existingByIdempotencyKey?.let { return it }

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

        val currency = Money.normalizeCurrency(order.currency)
        val amountMinor = when (paymentType) {
            PaymentType.CONSULTATION_FEE -> order.consultationFeeMinor
                ?: Money.toMinor(order.consultationFee, currency)
            PaymentType.BALANCE -> order.remainingAmountMinor
                ?: Money.toMinor(order.remainingAmount, currency)
            PaymentType.TRAVEL_GROUND_SERVICE_FEE -> {
                require(currency == TRAVEL_SERVICE_CURRENCY) { "TRAVEL_SERVICE_CURRENCY_MUST_BE_USD" }
                requireNotNull(order.travelGroundServiceFeeMinor) { "TRAVEL_GROUND_SERVICE_FEE_MISSING" }
            }
        }
        require(amountMinor > 0) { "PAYMENT_AMOUNT_NOT_POSITIVE" }
        val now = LocalDateTime.now()
        val expiresAt = if (paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE) {
            now.plusMinutes(PAYMENT_ATTEMPT_TIMEOUT_MINUTES)
        } else null
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
                expiresAt = expiresAt,
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
        if (payment.status in setOf(
                PaymentStatus.PARTIALLY_REFUNDED.name,
                PaymentStatus.REFUNDED.name
            )
        ) {
            return payment
        }
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
        if (payment.paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE.name &&
            result.status == PaymentStatus.SUCCEEDED
        ) {
            val resultAmountMinor = requireNotNull(result.amountMinor) { "PAYMENT_AMOUNT_MISSING" }
            val resultCurrency = requireNotNull(result.currency) { "PAYMENT_CURRENCY_MISSING" }
            require(resultAmountMinor == payment.amountMinor) { "PAYMENT_AMOUNT_MISMATCH" }
            require(Money.normalizeCurrency(resultCurrency) == payment.currency) { "PAYMENT_CURRENCY_MISMATCH" }
        } else {
            result.amountMinor?.let { require(it == payment.amountMinor) { "PAYMENT_AMOUNT_MISMATCH" } }
            result.currency?.let {
                require(Money.normalizeCurrency(it) == payment.currency) { "PAYMENT_CURRENCY_MISMATCH" }
            }
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
        if ((payment.status == PaymentStatus.REQUIRES_ACTION.name && result.status == PaymentStatus.CREATED) ||
            (payment.status == PaymentStatus.PROCESSING.name && result.status in setOf(
                PaymentStatus.CREATED,
                PaymentStatus.REQUIRES_ACTION
            ))
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
                expiresAt = earliestExpiry(payment.expiresAt, result.expiresAt),
                updatedAt = now
            )
        )
        if (result.status == PaymentStatus.SUCCEEDED) {
            val order = if (payment.paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE.name) {
                orderRepository.findByIdIncludeDeletedForUpdate(payment.orderId)
            } else {
                orderRepository.findByIdForUpdate(payment.orderId)
                    ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
            }
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
        if (payment.status in PaymentStatus.terminalDatabaseValues ||
            (payment.status == PaymentStatus.PROCESSING.name && status == PaymentStatus.FAILED)
        ) {
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
        order: OrderEntity?,
        now: LocalDateTime
    ): PaymentEntity {
        val type = PaymentType.valueOf(payment.paymentType)
        if (type == PaymentType.TRAVEL_GROUND_SERVICE_FEE) {
            if (order?.serviceActivatedAt != null) {
                val duplicate = paymentRepository.save(
                    payment.copy(
                        status = PaymentStatus.SUCCEEDED.name,
                        paidAt = payment.paidAt ?: now,
                        failureCode = DUPLICATE_PAYMENT_SUCCEEDED,
                        failureMessage = "A different successful payment already activated this order",
                        updatedAt = now
                    )
                )
                log.error(
                    "订单[{}]检测到第二笔旅游地接服务费成功扣款，保留渠道事实等待人工处理: paymentId={}, providerPaymentId={}",
                    order.orderNo,
                    duplicate.id,
                    duplicate.providerPaymentId
                )
                return duplicate
            }

            val activationFailure = travelServiceActivationFailure(payment, order)
            if (activationFailure != null) {
                val anomaly = paymentRepository.save(
                    payment.copy(
                        status = PaymentStatus.SUCCEEDED.name,
                        paidAt = payment.paidAt ?: now,
                        failureCode = PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE,
                        failureMessage = activationFailure.take(500),
                        updatedAt = now
                    )
                )
                log.error(
                    "旅游地接服务费渠道支付成功但订单不可激活，保留渠道事实等待人工退款: orderId={}, paymentId={}, reason={}",
                    payment.orderId,
                    payment.id,
                    activationFailure
                )
                return anomaly
            }
            checkNotNull(order)
            val feeMinor = checkNotNull(order.travelGroundServiceFeeMinor)
            val updatedOrder = order.copy(
                status = OrderStatusEnum.SERVICE_ACTIVE.value,
                serviceActivatedAt = now,
                paidAmount = Money.fromMinor(feeMinor, TRAVEL_SERVICE_CURRENCY),
                paidAmountMinor = feeMinor,
                updatedAt = now
            )
            orderRepository.save(updatedOrder)
            orderStatusLogService.logTransition(
                orderId = order.id,
                fromStatus = order.status,
                toStatus = updatedOrder.status,
                operatorId = payment.userId,
                operatorType = "USER",
                remark = "支付旅游地接服务费，激活地接服务"
            )
            val completed = paymentRepository.save(
                payment.copy(
                    status = PaymentStatus.SUCCEEDED.name,
                    paidAt = payment.paidAt ?: now,
                    failureCode = null,
                    failureMessage = null,
                    updatedAt = now
                )
            )
            log.info(
                "订单[{}]旅游地接服务费支付成功并激活服务，provider={}, paymentId={}",
                order.orderNo,
                payment.provider,
                payment.id
            )
            return completed
        }
        checkNotNull(order) { "ORDER_NOT_FOUND" }
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
            PaymentType.TRAVEL_GROUND_SERVICE_FEE -> error("UNREACHABLE_TRAVEL_SERVICE_ACTIVATION")
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
            PaymentType.TRAVEL_GROUND_SERVICE_FEE -> OrderStatusEnum.SERVICE_ACTIVE
        }
        require(current.canTransitionTo(target)) {
            when (paymentType) {
                PaymentType.BALANCE -> "当前状态[${current.value}]不允许支付尾款，需先完成到店核验"
                PaymentType.CONSULTATION_FEE -> "当前状态[${current.value}]不允许支付面诊金"
                PaymentType.TRAVEL_GROUND_SERVICE_FEE -> "当前状态[${current.value}]不允许支付旅游地接服务费"
            }
        }
    }

    private fun validatePaymentContract(
        order: OrderEntity,
        paymentType: PaymentType,
        provider: PaymentProvider,
        paymentMethod: String
    ) {
        if (order.paymentFlow == TRAVEL_SERVICE_PAYMENT_FLOW) {
            require(paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE) {
                MEDICAL_PAYMENT_NOT_SUPPORTED
            }
            require(provider == PaymentProvider.ALIPAY_PLUS) { "PAYMENT_PROVIDER_NOT_ALLOWED" }
            require(paymentMethod == TRAVEL_SERVICE_PAYMENT_METHOD) { "PAYMENT_METHOD_NOT_ALLOWED" }
        } else {
            require(paymentType != PaymentType.TRAVEL_GROUND_SERVICE_FEE) {
                "TRAVEL_SERVICE_PAYMENT_FLOW_REQUIRED"
            }
        }
    }

    private fun travelServiceActivationFailure(payment: PaymentEntity, order: OrderEntity?): String? {
        if (order == null) return "ORDER_NOT_FOUND"
        if (order.deletedAt != null) return "ORDER_SOFT_DELETED"
        if (order.paymentFlow != TRAVEL_SERVICE_PAYMENT_FLOW) return "TRAVEL_SERVICE_PAYMENT_FLOW_REQUIRED"
        if (order.status != OrderStatusEnum.PENDING_SERVICE_FEE.value) return "ORDER_STATUS_${order.status}"
        val feeMinor = order.travelGroundServiceFeeMinor ?: return "TRAVEL_GROUND_SERVICE_FEE_MISSING"
        val paymentAmountMinor = payment.amountMinor ?: return "PAYMENT_AMOUNT_MISSING"
        if (paymentAmountMinor != feeMinor) return "PAYMENT_AMOUNT_MISMATCH"
        if (runCatching { Money.normalizeCurrency(payment.currency) }.getOrNull() != TRAVEL_SERVICE_CURRENCY) {
            return "TRAVEL_SERVICE_CURRENCY_MUST_BE_USD"
        }
        if (runCatching { Money.normalizeCurrency(order.currency) }.getOrNull() != payment.currency) {
            return "PAYMENT_CURRENCY_MISMATCH"
        }
        return null
    }

    private fun earliestExpiry(
        localExpiry: LocalDateTime?,
        providerExpiry: LocalDateTime?
    ): LocalDateTime? = listOfNotNull(localExpiry, providerExpiry).minOrNull()

    private fun findSuccessfulPayment(orderId: String, type: PaymentType): PaymentEntity? =
        paymentRepository.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
            orderId,
            type.name,
            PaymentStatus.successfulDatabaseValues
        )
}
