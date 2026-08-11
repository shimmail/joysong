package com.joysong.server.settlement.service

import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementAllocationStatus
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigInteger
import java.time.LocalDateTime

data class RecoveryRequiredRevenueIssue(
    val refundId: String,
    val refundItemId: String,
    val allocationId: Long,
    val ownerType: String,
    val ownerId: String,
    val currency: String,
    val uncoveredMinor: Long
)

/** Task 7 replaces the no-op implementation with durable recovery workflow handling. */
interface RevenueIssueRecorder {
    fun recordRecoveryRequired(issue: RecoveryRequiredRevenueIssue)
}

@Service
class NoopRevenueIssueRecorder : RevenueIssueRecorder {
    override fun recordRecoveryRequired(issue: RecoveryRequiredRevenueIssue) = Unit
}

/** Appends wallet reversals after a provider refund is durably finalized. */
@Service
class SettlementReversalService(
    private val refundRepository: RefundRepository,
    private val refundItemRepository: RefundItemRepository,
    private val settlementRepository: SettlementRepository,
    private val allocationRepository: SettlementAllocationRepository,
    private val walletRepository: WalletRepository,
    private val walletLedgerService: WalletLedgerService,
    private val revenueIssueRecorder: RevenueIssueRecorder
) {
    @Transactional(rollbackFor = [Exception::class])
    fun reverseCompletedRefund(refundId: String) {
        val refund = refundRepository.findById(refundId)
            .orElseThrow { IllegalArgumentException("REFUND_NOT_FOUND") }
        require(refund.status == COMPLETED_REFUND_STATUS) { "REFUND_NOT_COMPLETED" }
        val completedItems = refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(refundId)
            .filter { it.status == COMPLETED_ITEM_STATUS && it.amountMinor > 0 }
        if (completedItems.isEmpty()) return

        val settlement = settlementRepository.findByOrderIdForUpdate(refund.orderId) ?: return
        val originalNetPaidMinor = requireNotNull(settlement.totalAmountMinor) { "SETTLEMENT_AMOUNT_MISSING" }
        require(originalNetPaidMinor > 0) { "SETTLEMENT_AMOUNT_NOT_POSITIVE" }
        val cumulativeRefundedMinor = refundItemRepository.sumCompletedAmountMinor(refund.orderId)
        require(cumulativeRefundedMinor in 0..originalNetPaidMinor) { "REFUND_AMOUNT_EXCEEDS_SETTLEMENT" }
        val allocations = allocationRepository.findAllBySettlementIdOrderByIdAscForUpdate(settlement.id)
        require(allocations.isNotEmpty()) { "SETTLEMENT_ALLOCATIONS_MISSING" }

        val walletBalances = mutableMapOf<WalletKey, WalletBalance>()
        val mutations = mutableListOf<WalletMutation>()
        allocations.forEach { allocation ->
            val target = cumulativeTarget(allocation, allocations, cumulativeRefundedMinor, originalNetPaidMinor)
            val required = target - allocation.reversedMinor
            if (required <= 0) return@forEach

            val key = WalletKey(allocation.ownerType.name, allocation.ownerId, settlement.currency)
            val balance = walletBalances.getOrPut(key) { WalletBalance.from(walletRepository.findForUpdate(key.ownerType, key.ownerId, key.currency)) }
            val bucket = debitBucket(allocation, balance)
            val coverable = minOf(required, balance.amount(bucket))
            if (coverable > 0) {
                allocation.reverse(coverable)
                balance.debit(bucket, coverable)
                mutations += splitMutations(
                    completedItems,
                    coverable,
                    allocation,
                    settlement.currency,
                    refundId,
                    bucket
                )
            }
            if (coverable < required) {
                revenueIssueRecorder.recordRecoveryRequired(
                    RecoveryRequiredRevenueIssue(
                        refundId = refundId,
                        refundItemId = completedItems.first().id,
                        allocationId = allocation.id,
                        ownerType = allocation.ownerType.name,
                        ownerId = allocation.ownerId,
                        currency = settlement.currency,
                        uncoveredMinor = required - coverable
                    )
                )
            }
        }

        if (mutations.isNotEmpty()) walletLedgerService.apply(mutations)
        val hasReversal = allocations.any { it.reversedMinor > 0 }
        if (hasReversal) {
            allocationRepository.saveAll(allocations)
            settlement.status = if (allocations.all { it.reversedMinor == it.amountMinor }) REVERSED_STATUS else PARTIALLY_REVERSED_STATUS
            settlement.updatedAt = LocalDateTime.now()
            settlementRepository.save(settlement)
        }
    }

    private fun cumulativeTarget(
        allocation: SettlementAllocationEntity,
        allocations: List<SettlementAllocationEntity>,
        refundedMinor: Long,
        totalMinor: Long
    ): Long {
        if (allocation.ownerType == SettlementAllocationOwnerType.DOCTOR) {
            val nonDoctorTargets = allocations.filter { it.ownerType != SettlementAllocationOwnerType.DOCTOR }
                .sumOf { proportionalTarget(it.amountMinor, refundedMinor, totalMinor) }
            return refundedMinor - nonDoctorTargets
        }
        return proportionalTarget(allocation.amountMinor, refundedMinor, totalMinor)
    }

    private fun proportionalTarget(amount: Long, refunded: Long, total: Long): Long =
        BigInteger.valueOf(amount)
            .multiply(BigInteger.valueOf(refunded))
            .divide(BigInteger.valueOf(total))
            .longValueExact()

    private fun splitMutations(
        items: List<RefundItemEntity>,
        amount: Long,
        allocation: SettlementAllocationEntity,
        currency: String,
        refundId: String,
        bucket: BalanceBucket
    ): List<WalletMutation> {
        val itemTotal = items.sumOf { it.amountMinor }
        var remaining = amount
        return items.mapIndexedNotNull { index, item ->
            val share = if (index == items.lastIndex) remaining else proportionalTarget(amount, item.amountMinor, itemTotal)
            remaining -= share
            if (share == 0L) null else WalletMutation(
                ownerType = allocation.ownerType.name,
                ownerId = allocation.ownerId,
                currency = currency,
                allocationId = allocation.id,
                pendingDelta = if (bucket == BalanceBucket.PENDING) -share else 0,
                availableDelta = if (bucket == BalanceBucket.AVAILABLE) -share else 0,
                frozenDelta = 0,
                entryType = REVERSAL_ENTRY_TYPE,
                sourceType = REFUND_SOURCE_TYPE,
                sourceId = refundId,
                operationKey = "refund:reverse:${item.id}:${allocation.id}"
            )
        }
    }

    private fun debitBucket(allocation: SettlementAllocationEntity, balance: WalletBalance): BalanceBucket = when (allocation.status) {
        SettlementAllocationStatus.PENDING -> BalanceBucket.PENDING
        SettlementAllocationStatus.AVAILABLE -> BalanceBucket.AVAILABLE
        SettlementAllocationStatus.PARTIALLY_REVERSED -> if (balance.available > 0) BalanceBucket.AVAILABLE else BalanceBucket.PENDING
        SettlementAllocationStatus.REVERSED -> throw IllegalStateException("REVERSED_ALLOCATION_HAS_UNPOSTED_REVERSAL")
    }

    private enum class BalanceBucket { PENDING, AVAILABLE }

    private data class WalletKey(val ownerType: String, val ownerId: String, val currency: String)

    private data class WalletBalance(var pending: Long, var available: Long) {
        fun amount(bucket: BalanceBucket): Long = if (bucket == BalanceBucket.PENDING) pending else available
        fun debit(bucket: BalanceBucket, amount: Long) {
            if (bucket == BalanceBucket.PENDING) pending -= amount else available -= amount
        }

        companion object {
            fun from(wallet: WalletEntity?): WalletBalance = WalletBalance(wallet?.pendingMinor ?: 0, wallet?.availableMinor ?: 0)
        }
    }

    private companion object {
        const val COMPLETED_REFUND_STATUS = "APPROVED"
        const val COMPLETED_ITEM_STATUS = "SUCCEEDED"
        const val REVERSAL_ENTRY_TYPE = "REVERSAL"
        const val REFUND_SOURCE_TYPE = "REFUND"
        const val PARTIALLY_REVERSED_STATUS = "PARTIALLY_REVERSED"
        const val REVERSED_STATUS = "REVERSED"
    }
}
