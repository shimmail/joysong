package com.joysong.server.reconciliation.service

import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.reconciliation.repository.ReconciliationIssueRepository
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.service.RecoveryRequiredRevenueIssue
import com.joysong.server.settlement.service.RevenueIssueRecorder
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReconciliationResult(
    val objectType: String,
    val objectId: Long,
    val checkedTypes: Set<String>,
    val mismatchedTypes: Set<String>
) {
    val isMatched: Boolean get() = mismatchedTypes.isEmpty()
}

/**
 * Read-only over revenue facts. Its only writes are reconciliation issue upserts/resolutions.
 */
@Service
class RevenueReconciliationService(
    private val settlementRepository: SettlementRepository,
    private val allocationRepository: SettlementAllocationRepository,
    private val paymentRepository: PaymentRepository,
    private val refundItemRepository: RefundItemRepository,
    private val walletRepository: WalletRepository,
    private val ledgerRepository: WalletLedgerEntryRepository,
    private val issueRepository: ReconciliationIssueRepository
) : RevenueIssueRecorder {

    @Transactional
    fun reconcileSettlement(settlementId: Long): ReconciliationResult {
        val settlement = settlementRepository.findById(settlementId)
            .orElseThrow { IllegalArgumentException("SETTLEMENT_NOT_FOUND") }
        val mismatches = linkedSetOf<String>()
        val total = requireNotNull(settlement.totalAmountMinor) { "SETTLEMENT_AMOUNT_MISSING" }
        val orderNet = Math.subtractExact(
            paymentRepository.sumSucceededAmountMinor(settlement.orderId),
            refundItemRepository.sumCompletedAmountMinor(settlement.orderId)
        )
        verify(
            issueType = ORDER_NET_VS_SETTLEMENT,
            objectType = SETTLEMENT_OBJECT,
            objectId = settlementId.toString(),
            expected = orderNet,
            actual = total,
            currency = settlement.currency,
            details = "successful_payment_minor_minus_completed_refund_minor",
            mismatches = mismatches
        )

        val allocations = allocationRepository.findAllBySettlementIdOrderByIdAsc(settlementId)
        val allocationTotal = allocations.fold(0L) { sum, allocation -> Math.addExact(sum, allocation.amountMinor) }
        val rolesComplete = REQUIRED_ROLES.all { role -> allocations.count { it.ownerType == role } == 1 }
        verify(
            issueType = SETTLEMENT_VS_ALLOCATIONS,
            objectType = SETTLEMENT_OBJECT,
            objectId = settlementId.toString(),
            expected = total,
            actual = allocationTotal,
            currency = settlement.currency,
            details = "required_roles_complete=$rolesComplete",
            mismatches = mismatches,
            matches = allocationTotal == total && rolesComplete
        )

        allocations.forEach { allocation -> reconcileAllocation(allocation, settlement.currency, mismatches) }
        return ReconciliationResult(SETTLEMENT_OBJECT, settlementId, SETTLEMENT_CHECKS, mismatches)
    }

    @Transactional
    fun reconcileWallet(walletId: Long): ReconciliationResult {
        val wallet = walletRepository.findById(walletId)
            .orElseThrow { IllegalArgumentException("WALLET_NOT_FOUND") }
        val entries = ledgerRepository.findAllByWalletIdOrderByIdAsc(walletId)
        val accumulated = entries.fold(Balances.ZERO) { balances, entry -> balances.plus(entry) }
        val latest = entries.lastOrNull()?.let { Balances(it.pendingBalanceMinor, it.availableBalanceMinor, it.frozenBalanceMinor) }
            ?: Balances.ZERO
        val projected = Balances(wallet.pendingMinor, wallet.availableMinor, wallet.frozenMinor)
        val actual = latest.total()
        verify(
            issueType = WALLET_VS_LEDGER,
            objectType = WALLET_OBJECT,
            objectId = walletId.toString(),
            expected = projected.total(),
            actual = actual,
            currency = wallet.currency,
            details = "projection=$projected latest_snapshot=$latest cumulative_deltas=$accumulated",
            mismatches = linkedSetOf(),
            matches = projected == latest && projected == accumulated && latest == accumulated
        ).also { mismatch ->
            return ReconciliationResult(WALLET_OBJECT, walletId, setOf(WALLET_VS_LEDGER), mismatch)
        }
    }

    @Transactional
    override fun recordRecoveryRequired(issue: RecoveryRequiredRevenueIssue) {
        recordIssue(
            issueType = RECOVERY_REQUIRED,
            objectType = ALLOCATION_OBJECT,
            objectId = issue.allocationId.toString(),
            expected = issue.uncoveredMinor,
            actual = 0,
            currency = issue.currency,
            severity = CRITICAL_SEVERITY,
            details = "refund_id=${issue.refundId};refund_item_id=${issue.refundItemId};owner_type=${issue.ownerType};owner_id=${issue.ownerId}"
        )
    }

    private fun reconcileAllocation(allocation: SettlementAllocationEntity, currency: String, mismatches: MutableSet<String>) {
        val entries = ledgerRepository.findAllByAllocationIdOrderByIdAsc(allocation.id)
        val actual = entries.fold(Balances.ZERO) { balances, entry -> balances.plus(entry) }
        val remaining = Math.subtractExact(allocation.amountMinor, allocation.reversedMinor)
        val expected = when {
            remaining == 0L -> Balances.ZERO
            allocation.balanceBucket == SettlementAllocationBalanceBucket.PENDING -> Balances(remaining, 0, 0)
            else -> Balances(0, remaining, 0)
        }
        verify(
            issueType = ALLOCATION_VS_LEDGER,
            objectType = ALLOCATION_OBJECT,
            objectId = allocation.id.toString(),
            expected = expected.total(),
            actual = actual.total(),
            currency = currency,
            details = "expected_bucket=$expected actual_ledger=$actual status=${allocation.status}",
            mismatches = mismatches,
            matches = expected == actual
        )
    }

    private fun verify(
        issueType: String,
        objectType: String,
        objectId: String,
        expected: Long,
        actual: Long,
        currency: String,
        details: String,
        mismatches: MutableSet<String>,
        matches: Boolean = expected == actual
    ): MutableSet<String> {
        if (matches) {
            issueRepository.resolveActiveIssue(issueType, objectType, objectId)
        } else {
            recordIssue(issueType, objectType, objectId, expected, actual, currency, ERROR_SEVERITY, details)
            mismatches += issueType
        }
        return mismatches
    }

    private fun recordIssue(
        issueType: String,
        objectType: String,
        objectId: String,
        expected: Long,
        actual: Long,
        currency: String?,
        severity: String,
        details: String
    ) {
        // The generated active_key unique index makes this native upsert atomic across concurrent detections.
        issueRepository.upsertActiveIssue(issueType, objectType, objectId, expected, actual, currency, severity, details)
    }

    private data class Balances(val pending: Long, val available: Long, val frozen: Long) {
        fun plus(entry: WalletLedgerEntryEntity) = Balances(
            Math.addExact(pending, entry.pendingDeltaMinor),
            Math.addExact(available, entry.availableDeltaMinor),
            Math.addExact(frozen, entry.frozenDeltaMinor)
        )

        fun total(): Long = Math.addExact(Math.addExact(pending, available), frozen)

        companion object { val ZERO = Balances(0, 0, 0) }
    }

    private companion object {
        const val SETTLEMENT_OBJECT = "SETTLEMENT"
        const val ALLOCATION_OBJECT = "ALLOCATION"
        const val WALLET_OBJECT = "WALLET"
        const val ORDER_NET_VS_SETTLEMENT = "ORDER_NET_VS_SETTLEMENT"
        const val SETTLEMENT_VS_ALLOCATIONS = "SETTLEMENT_VS_ALLOCATIONS"
        const val ALLOCATION_VS_LEDGER = "ALLOCATION_VS_LEDGER"
        const val WALLET_VS_LEDGER = "WALLET_VS_LEDGER"
        const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
        const val ERROR_SEVERITY = "ERROR"
        const val CRITICAL_SEVERITY = "CRITICAL"
        val REQUIRED_ROLES = setOf(
            com.joysong.server.settlement.entity.SettlementAllocationOwnerType.PLATFORM,
            com.joysong.server.settlement.entity.SettlementAllocationOwnerType.INSTITUTION,
            com.joysong.server.settlement.entity.SettlementAllocationOwnerType.CONSULTANT,
            com.joysong.server.settlement.entity.SettlementAllocationOwnerType.DOCTOR
        )
        val SETTLEMENT_CHECKS = setOf(ORDER_NET_VS_SETTLEMENT, SETTLEMENT_VS_ALLOCATIONS, ALLOCATION_VS_LEDGER)
    }
}
