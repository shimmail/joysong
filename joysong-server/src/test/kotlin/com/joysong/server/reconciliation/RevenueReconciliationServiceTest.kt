package com.joysong.server.reconciliation

import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.reconciliation.entity.ReconciliationIssueEntity
import com.joysong.server.reconciliation.repository.ReconciliationIssueRepository
import com.joysong.server.reconciliation.service.RevenueReconciliationService
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementAllocationStatus
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Optional

class RevenueReconciliationServiceTest {
    @MockK private lateinit var settlementRepository: SettlementRepository
    @MockK private lateinit var allocationRepository: SettlementAllocationRepository
    @MockK private lateinit var paymentRepository: PaymentRepository
    @MockK private lateinit var refundItemRepository: RefundItemRepository
    @MockK private lateinit var walletRepository: WalletRepository
    @MockK private lateinit var ledgerRepository: WalletLedgerEntryRepository
    @MockK private lateinit var issueRepository: ReconciliationIssueRepository

    private lateinit var service: RevenueReconciliationService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service = RevenueReconciliationService(
            settlementRepository, allocationRepository, paymentRepository, refundItemRepository,
            walletRepository, ledgerRepository, issueRepository
        )
        every { issueRepository.upsertActiveIssue(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { issueRepository.resolveActiveIssue(any(), any(), any()) } returns 0
    }

    @Test
    fun `matching settlement reconciles every settlement check without money writes`() {
        arrangeSettlement(total = 100, payments = 120, refunds = 20, allocations = allocations())
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(any()) } answers {
            ledgerForAllocation(firstArg<Long>())
        }

        val result = service.reconcileSettlement(1)

        assertTrue(result.isMatched)
        verify(exactly = 1) { issueRepository.resolveActiveIssue("ORDER_NET_VS_SETTLEMENT", "SETTLEMENT", "1") }
        verify(exactly = 1) { issueRepository.resolveActiveIssue("SETTLEMENT_VS_ALLOCATIONS", "SETTLEMENT", "1") }
        verify(exactly = 0) {
            settlementRepository.save(any())
            allocationRepository.save(any())
            walletRepository.save(any())
            ledgerRepository.save(any())
        }
    }

    @Test
    fun `settlement mismatch creates one durable issue and a later match resolves it`() {
        arrangeSettlement(total = 100, payments = 120, refunds = 10, allocations = allocations())
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(any()) } answers { ledgerForAllocation(firstArg<Long>()) }

        val mismatch = service.reconcileSettlement(1)
        every { refundItemRepository.sumCompletedAmountMinor("order-1") } returns 20
        val matched = service.reconcileSettlement(1)

        assertFalse(mismatch.isMatched)
        assertTrue(matched.isMatched)
        verify(exactly = 1) {
            issueRepository.upsertActiveIssue(
                "ORDER_NET_VS_SETTLEMENT", "SETTLEMENT", "1", 110, 100, "USD", "ERROR", any()
            )
        }
        verify(exactly = 1) { issueRepository.resolveActiveIssue("ORDER_NET_VS_SETTLEMENT", "SETTLEMENT", "1") }
    }

    @Test
    fun `repeated allocation mismatch uses the single active issue upsert contract`() {
        arrangeSettlement(total = 100, payments = 100, refunds = 0, allocations = allocations().dropLast(1))
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(any()) } returns emptyList()

        service.reconcileSettlement(1)
        service.reconcileSettlement(1)

        verify(exactly = 2) {
            issueRepository.upsertActiveIssue(
                "SETTLEMENT_VS_ALLOCATIONS", "SETTLEMENT", "1", 100, 75, "USD", "ERROR", any()
            )
        }
    }

    @Test
    fun `allocation ledger mismatch creates allocation issue`() {
        arrangeSettlement(total = 100, payments = 100, refunds = 0, allocations = allocations())
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(1) } returns listOf(ledger(allocationId = 1, pending = 20))
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(2) } returns ledgerForAllocation(2)
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(3) } returns ledgerForAllocation(3)
        every { ledgerRepository.findAllByAllocationIdOrderByIdAsc(4) } returns ledgerForAllocation(4)

        service.reconcileSettlement(1)

        verify(exactly = 1) {
            issueRepository.upsertActiveIssue("ALLOCATION_VS_LEDGER", "ALLOCATION", "1", 25, 20, "USD", "ERROR", any())
        }
    }

    @Test
    fun `wallet snapshots and cumulative deltas both must agree with wallet projection`() {
        every { walletRepository.findById(9) } returns Optional.of(wallet(id = 9, pending = 10))
        every { ledgerRepository.findAllByWalletIdOrderByIdAsc(9) } returns listOf(
            ledger(walletId = 9, pending = 10, snapshotPending = 9)
        )

        val result = service.reconcileWallet(9)

        assertFalse(result.isMatched)
        verify(exactly = 1) {
            issueRepository.upsertActiveIssue("WALLET_VS_LEDGER", "WALLET", "9", 10, 9, "USD", "ERROR", any())
        }
    }

    @Test
    fun `recovery recorder stores durable recovery issue through atomic upsert`() {
        service.recordRecoveryRequired(
            com.joysong.server.settlement.service.RecoveryRequiredRevenueIssue(
                refundId = "refund-1", refundItemId = "item-1", allocationId = 7,
                ownerType = "DOCTOR", ownerId = "doctor-1", currency = "USD", uncoveredMinor = 35
            )
        )

        verify(exactly = 1) {
            issueRepository.upsertActiveIssue("RECOVERY_REQUIRED", "ALLOCATION", "7", 35, 0, "USD", "CRITICAL", any())
        }
    }

    @Test
    fun `missing settlement and wallet fail explicitly`() {
        every { settlementRepository.findById(404) } returns Optional.empty()
        every { walletRepository.findById(405) } returns Optional.empty()

        assertEquals("SETTLEMENT_NOT_FOUND", assertThrows<IllegalArgumentException> { service.reconcileSettlement(404) }.message)
        assertEquals("WALLET_NOT_FOUND", assertThrows<IllegalArgumentException> { service.reconcileWallet(405) }.message)
    }

    private fun arrangeSettlement(total: Long, payments: Long, refunds: Long, allocations: List<SettlementAllocationEntity>) {
        every { settlementRepository.findById(1) } returns Optional.of(
            SettlementEntity(id = 1, orderId = "order-1", totalAmountMinor = total, currency = "USD")
        )
        every { paymentRepository.sumSucceededAmountMinor("order-1") } returns payments
        every { refundItemRepository.sumCompletedAmountMinor("order-1") } returns refunds
        every { allocationRepository.findAllBySettlementIdOrderByIdAsc(1) } returns allocations
    }

    private fun allocations() = listOf(
        allocation(1, SettlementAllocationOwnerType.PLATFORM), allocation(2, SettlementAllocationOwnerType.INSTITUTION),
        allocation(3, SettlementAllocationOwnerType.CONSULTANT), allocation(4, SettlementAllocationOwnerType.DOCTOR)
    )

    private fun allocation(id: Long, owner: SettlementAllocationOwnerType) = SettlementAllocationEntity(
        id = id, settlementId = 1, ownerType = owner, ownerId = "owner-$id", ownerName = "owner-$id",
        rate = BigDecimal.ZERO, amountMinor = 25, balanceBucket = SettlementAllocationBalanceBucket.PENDING,
        status = SettlementAllocationStatus.PENDING
    )

    private fun ledgerForAllocation(id: Long) = listOf(ledger(allocationId = id, pending = 25, snapshotPending = 25))

    private fun ledger(
        walletId: Long = 1, allocationId: Long? = null, pending: Long = 0, available: Long = 0, frozen: Long = 0,
        snapshotPending: Long = pending, snapshotAvailable: Long = available, snapshotFrozen: Long = frozen
    ) = WalletLedgerEntryEntity(
        walletId = walletId, allocationId = allocationId, entryType = "CREDIT", pendingDeltaMinor = pending,
        availableDeltaMinor = available, frozenDeltaMinor = frozen, pendingBalanceMinor = snapshotPending,
        availableBalanceMinor = snapshotAvailable, frozenBalanceMinor = snapshotFrozen, sourceType = "SETTLEMENT",
        sourceId = "1", operationKey = "op-$walletId-${allocationId ?: 0}-$pending-$available-$frozen"
    )

    private fun wallet(id: Long, pending: Long = 0, available: Long = 0, frozen: Long = 0) =
        WalletEntity(id = id, ownerType = "DOCTOR", ownerId = "doctor-1", currency = "USD").also {
            it.applyDeltas(pending, available, frozen)
        }
}
