package com.joysong.server.settlement.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.entity.SettlementAllocationStatus
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** Releases one due settlement inside its own transaction. */
@Service
class SettlementReleaseService(
    private val settlementRepository: SettlementRepository,
    private val allocationRepository: SettlementAllocationRepository,
    private val orderRepository: OrderRepository,
    private val walletLedgerService: WalletLedgerService,
    private val orderStatusLogService: OrderStatusLogService
) {
    @Transactional(rollbackFor = [Exception::class])
    fun release(settlementId: Long, now: LocalDateTime) {
        val settlement = settlementRepository.findByIdForUpdate(settlementId) ?: return
        if (settlement.status in TERMINAL_STATUSES) return
        check(settlement.status == PENDING_STATUS) { "不支持的结算状态: ${settlement.status}" }

        val allocations = allocationRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        val order = orderRepository.findByIdForUpdate(settlement.orderId)
            ?: throw IllegalStateException("订单不存在: ${settlement.orderId}")
        check(order.status == OrderStatusEnum.PENDING_SETTLEMENT.value) {
            "订单状态不是PENDING_SETTLEMENT: ${settlement.orderId}"
        }

        walletLedgerService.apply(
            allocations.mapNotNull { allocation ->
                val remaining = allocation.amountMinor - allocation.reversedMinor
                if (remaining <= 0) {
                    null
                } else {
                    WalletMutation(
                        ownerType = allocation.ownerType.name,
                        ownerId = allocation.ownerId,
                        currency = settlement.currency,
                        allocationId = allocation.id,
                        pendingDelta = -remaining,
                        availableDelta = remaining,
                        frozenDelta = 0,
                        entryType = RELEASE_ENTRY_TYPE,
                        sourceType = SETTLEMENT_SOURCE_TYPE,
                        sourceId = settlement.id.toString(),
                        operationKey = "settlement:release:${settlement.id}:${allocation.id}"
                    )
                }
            }
        )

        allocations.filter {
            it.balanceBucket == SettlementAllocationBalanceBucket.PENDING &&
                it.status != SettlementAllocationStatus.REVERSED &&
                it.amountMinor > it.reversedMinor
        }
            .onEach { it.markAvailable() }
            .takeIf { it.isNotEmpty() }
            ?.let { allocationRepository.saveAll(it) }

        settlement.status = AVAILABLE_STATUS
        settlement.updatedAt = now
        settlementRepository.save(settlement)
        orderRepository.save(order.copy(status = OrderStatusEnum.SETTLED.value, updatedAt = now))
        orderStatusLogService.logTransition(
            orderId = order.id,
            fromStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
            toStatus = OrderStatusEnum.SETTLED.value,
            operatorId = null,
            operatorType = "SYSTEM",
            remark = "结算到期自动结算"
        )
    }

    private companion object {
        const val PENDING_STATUS = "PENDING"
        const val AVAILABLE_STATUS = "AVAILABLE"
        const val RELEASE_ENTRY_TYPE = "RELEASE"
        const val SETTLEMENT_SOURCE_TYPE = "SETTLEMENT"
        val TERMINAL_STATUSES = setOf("AVAILABLE", "PARTIALLY_REVERSED", "REVERSED")
    }
}
