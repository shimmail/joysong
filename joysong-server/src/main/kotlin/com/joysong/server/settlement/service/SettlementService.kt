package com.joysong.server.settlement.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderSplitRates
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.Money
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime

/** Creates immutable settlement snapshots and their corresponding pending wallet credits. */
@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val allocationRepository: SettlementAllocationRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val refundItemRepository: RefundItemRepository,
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val doctorInstitutionProjectConfigRepository: DoctorInstitutionProjectConfigRepository,
    private val settlementAmountAllocator: SettlementAmountAllocator,
    private val walletLedgerService: WalletLedgerService,
    private val orderStatusLogService: OrderStatusLogService,
    private val transactionManager: PlatformTransactionManager? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(SettlementService::class.java)
        private const val PLATFORM_ID = "PLATFORM"
        private const val PLATFORM_NAME = "平台"
        private const val SETTLEMENT_ENTRY_TYPE = "SETTLEMENT"
    }

    /**
     * The outer transaction stays usable after a losing unique-key race. The actual creation
     * runs in a fresh transaction so a duplicate-key failure can be rolled back before reload.
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSettlement(orderId: String): SettlementEntity = try {
        inNewTransaction { createSettlement(orderId) }
    } catch (error: DataIntegrityViolationException) {
        if (error.hasSettlementOrderUniqueViolation()) {
            settlementRepository.findByOrderId(orderId) ?: throw error
        } else {
            throw error
        }
    }

    private fun createSettlement(orderId: String): SettlementEntity {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        settlementRepository.findByOrderId(orderId)?.let { return it }

        validateRecipientSnapshot(order)
        val netPaidMinor = paymentRepository.sumSucceededAmountMinor(orderId) -
            refundItemRepository.sumCompletedAmountMinor(orderId)
        require(netPaidMinor > 0) { "ORDER_NET_PAID_NOT_POSITIVE" }

        val rates = resolveRates(order)
        val amounts = settlementAmountAllocator.allocate(netPaidMinor, rates)
        val savedSettlement = settlementRepository.saveAndFlush(
            SettlementEntity(
                orderId = orderId,
                totalAmount = Money.fromMinor(netPaidMinor, order.currency),
                platformAmount = Money.fromMinor(amounts.platform, order.currency),
                institutionAmount = Money.fromMinor(amounts.institution, order.currency),
                consultantAmount = Money.fromMinor(amounts.consultant, order.currency),
                doctorAmount = Money.fromMinor(amounts.doctor, order.currency),
                currency = order.currency,
                totalAmountMinor = netPaidMinor,
                platformAmountMinor = amounts.platform,
                institutionAmountMinor = amounts.institution,
                consultantAmountMinor = amounts.consultant,
                doctorAmountMinor = amounts.doctor,
                platformRate = rates.platformRate,
                institutionRate = rates.institutionRate,
                consultantRate = rates.consultantRate,
                doctorRate = rates.doctorRate,
                status = "PENDING",
                settledAt = order.settlementAt
            )
        )
        val allocations = allocationRepository.saveAll(
            listOf(
                allocation(savedSettlement.id, SettlementAllocationOwnerType.PLATFORM, PLATFORM_ID, PLATFORM_NAME, rates.platformRate, amounts.platform),
                allocation(savedSettlement.id, SettlementAllocationOwnerType.INSTITUTION, order.institutionId, order.institutionName, rates.institutionRate, amounts.institution),
                allocation(savedSettlement.id, SettlementAllocationOwnerType.CONSULTANT, order.consultantId, order.consultantName, rates.consultantRate, amounts.consultant),
                allocation(savedSettlement.id, SettlementAllocationOwnerType.DOCTOR, order.doctorId, order.doctorName, rates.doctorRate, amounts.doctor)
            )
        )
        walletLedgerService.apply(allocations.map { allocation ->
            WalletMutation(
                ownerType = allocation.ownerType.name,
                ownerId = allocation.ownerId,
                currency = order.currency,
                allocationId = allocation.id,
                pendingDelta = allocation.amountMinor,
                availableDelta = 0,
                frozenDelta = 0,
                entryType = SETTLEMENT_ENTRY_TYPE,
                sourceType = SETTLEMENT_ENTRY_TYPE,
                sourceId = savedSettlement.id.toString(),
                operationKey = "settlement:create:${savedSettlement.id}:${allocation.id}"
            )
        })
        log.info("订单[{}]结算快照已创建[id={}, netPaidMinor={}]", orderId, savedSettlement.id, netPaidMinor)
        return savedSettlement
    }

    private fun validateRecipientSnapshot(order: OrderEntity) {
        check(
            order.institutionId.isNotBlank() && order.institutionName.isNotBlank() &&
                order.institutionProjectId.isNotBlank() &&
                order.consultantId.isNotBlank() && order.consultantName.isNotBlank() &&
                order.doctorId.isNotBlank() && order.doctorName.isNotBlank()
        ) { "订单分账信息不完整，缺少机构、机构项目、医美顾问或医生快照" }
    }

    private fun resolveRates(order: OrderEntity): OrderSplitRates {
        val config = doctorInstitutionProjectConfigRepository
            .findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
        return splitRatePolicy.resolve(
            institutionRate = config?.institutionRate ?: splitRatePolicy.defaultInstitutionRate(),
            consultantRate = config?.commissionRate ?: BigDecimal.ZERO
        )
    }

    private fun allocation(
        settlementId: Long,
        ownerType: SettlementAllocationOwnerType,
        ownerId: String,
        ownerName: String,
        rate: BigDecimal,
        amountMinor: Long
    ) = SettlementAllocationEntity(
        settlementId = settlementId,
        ownerType = ownerType,
        ownerId = ownerId,
        ownerName = ownerName,
        rate = rate,
        amountMinor = amountMinor
    )

    private fun <T> inNewTransaction(action: () -> T): T = transactionManager?.let { manager ->
        TransactionTemplate(manager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }.execute { action() } ?: error("结算事务未返回结果")
    } ?: action()

    private fun Throwable.hasSettlementOrderUniqueViolation(): Boolean = generateSequence(this) { it.cause }
        .any { it.message?.contains("uk_settlements_order_id", ignoreCase = true) == true }

    @Transactional(rollbackFor = [Exception::class])
    fun processDueSettlements() {
        val now = LocalDateTime.now()
        val dueSettlements = settlementRepository.findByStatusAndSettledAtBefore("PENDING", now)
        dueSettlements.forEach { settlement ->
            settlement.status = "COMPLETED"
            settlement.updatedAt = now
            settlementRepository.save(settlement)
            log.info("结算记录[id={}]已到期完成, 订单ID: {}", settlement.id, settlement.orderId)
            val order = orderRepository.findById(settlement.orderId).orElse(null)
            if (order != null && order.status == OrderStatusEnum.PENDING_SETTLEMENT.value) {
                orderRepository.save(
                    order.copy(
                        status = OrderStatusEnum.SETTLED.value,
                        updatedAt = now
                    )
                )
                orderStatusLogService.logTransition(
                    orderId = order.id,
                    fromStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
                    toStatus = OrderStatusEnum.SETTLED.value,
                    operatorId = null,
                    operatorType = "SYSTEM",
                    remark = "结算到期自动结算"
                )
                log.info("订单[{}]状态更新: PENDING_SETTLEMENT -> SETTLED", order.id)
            } else {
                log.warn("订单[{}]状态不是PENDING_SETTLEMENT(当前: {}), 跳过状态更新", settlement.orderId, order?.status)
            }
        }
        log.info("本次共处理{}条到期结算", dueSettlements.size)
    }

    fun getByOrderId(orderId: String): SettlementEntity? = settlementRepository.findByOrderId(orderId)
}
