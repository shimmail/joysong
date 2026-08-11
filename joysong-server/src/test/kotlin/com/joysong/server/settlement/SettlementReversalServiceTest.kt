package com.joysong.server.settlement

import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementAllocationStatus
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.RecoveryRequiredRevenueIssue
import com.joysong.server.settlement.service.RevenueIssueRecorder
import com.joysong.server.settlement.service.SettlementReversalService
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class SettlementReversalServiceTest {
    @MockK private lateinit var refundRepository: RefundRepository
    @MockK private lateinit var refundItemRepository: RefundItemRepository
    @MockK private lateinit var settlementRepository: SettlementRepository
    @MockK private lateinit var allocationRepository: SettlementAllocationRepository
    @MockK private lateinit var walletRepository: WalletRepository
    @MockK private lateinit var walletLedgerEntryRepository: WalletLedgerEntryRepository
    @MockK private lateinit var walletLedgerService: WalletLedgerService
    @MockK private lateinit var revenueIssueRecorder: RevenueIssueRecorder

    private lateinit var service: SettlementReversalService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service = SettlementReversalService(
            refundRepository,
            refundItemRepository,
            settlementRepository,
            allocationRepository,
            walletRepository,
            walletLedgerEntryRepository,
            walletLedgerService,
            revenueIssueRecorder
        )
    }

    @Test
    fun `pending allocation reversal debits pending wallet and appends reversal`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.PENDING)
        val mutations = slot<List<WalletMutation>>()
        arrange(refundAmount = 40, cumulativeAmount = 40, allocations = listOf(allocation), wallet = wallet(pending = 100))
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        service.reverseCompletedRefund("refund-1")

        assertEquals(40, allocation.reversedMinor)
        assertEquals(SettlementAllocationStatus.PARTIALLY_REVERSED, allocation.status)
        assertEquals(-40, mutations.captured.single().pendingDelta)
        assertEquals(0, mutations.captured.single().availableDelta)
        assertEquals("refund:reverse:item-1:1", mutations.captured.single().operationKey)
        assertEquals("REVERSAL", mutations.captured.single().entryType)
    }

    @Test
    fun `available allocation reversal debits available wallet`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.AVAILABLE)
        val mutations = slot<List<WalletMutation>>()
        arrange(refundAmount = 40, cumulativeAmount = 40, allocations = listOf(allocation), wallet = wallet(available = 100))
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        service.reverseCompletedRefund("refund-1")

        assertEquals(-40, mutations.captured.single().availableDelta)
        assertEquals(0, mutations.captured.single().pendingDelta)
    }

    @Test
    fun `two partial refunds followed by full refund conserve each allocation total`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.PENDING)
        val wallet = wallet(pending = 100)
        arrange(refundAmount = 50, cumulativeAmount = 50, allocations = listOf(allocation), wallet = wallet)
        every { walletLedgerService.apply(any()) } returns emptyList()

        service.reverseCompletedRefund("refund-1")
        assertEquals(50, allocation.reversedMinor)
        arrange(refundId = "refund-2", refundAmount = 50, cumulativeAmount = 100, allocations = listOf(allocation), wallet = wallet)
        service.reverseCompletedRefund("refund-2")

        assertEquals(100, allocation.reversedMinor)
        assertEquals(SettlementAllocationStatus.REVERSED, allocation.status)
    }

    @Test
    fun `duplicate refund delivery creates no duplicate reversal ledger entry`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.PENDING)
        arrange(refundAmount = 50, cumulativeAmount = 50, allocations = listOf(allocation), wallet = wallet(pending = 100))
        every { walletLedgerService.apply(any()) } returns emptyList()

        service.reverseCompletedRefund("refund-1")
        service.reverseCompletedRefund("refund-1")

        verify(exactly = 1) { walletLedgerService.apply(any()) }
        assertEquals(50, allocation.reversedMinor)
    }

    @Test
    fun `one cent cumulative rounding remainder is assigned to doctor`() {
        val platform = allocation(id = 1, amount = 33, owner = SettlementAllocationOwnerType.PLATFORM)
        val institution = allocation(id = 2, amount = 33, owner = SettlementAllocationOwnerType.INSTITUTION)
        val doctor = allocation(id = 3, amount = 34, owner = SettlementAllocationOwnerType.DOCTOR)
        val mutations = slot<List<WalletMutation>>()
        arrange(
            refundAmount = 1,
            cumulativeAmount = 1,
            allocations = listOf(platform, institution, doctor),
            wallet = wallet(pending = 100)
        )
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        service.reverseCompletedRefund("refund-1")

        assertEquals(0, platform.reversedMinor)
        assertEquals(0, institution.reversedMinor)
        assertEquals(1, doctor.reversedMinor)
        assertEquals(listOf(3L), mutations.captured.mapNotNull { it.allocationId })
    }

    @Test
    fun `available insufficiency reverses coverable amount and records recovery`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.AVAILABLE)
        val mutations = slot<List<WalletMutation>>()
        val issue = slot<RecoveryRequiredRevenueIssue>()
        arrange(refundAmount = 50, cumulativeAmount = 50, allocations = listOf(allocation), wallet = wallet(available = 30))
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()
        every { revenueIssueRecorder.recordRecoveryRequired(capture(issue)) } just io.mockk.Runs

        service.reverseCompletedRefund("refund-1")

        assertEquals(30, allocation.reversedMinor)
        assertEquals(-30, mutations.captured.single().availableDelta)
        assertEquals(20, issue.captured.uncoveredMinor)
        assertEquals(1, issue.captured.allocationId)
    }

    @Test
    fun `retry after partial cover never consumes replenished funds for an existing refund operation`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.AVAILABLE)
        val issues = mutableListOf<RecoveryRequiredRevenueIssue>()
        arrange(refundAmount = 50, cumulativeAmount = 50, allocations = listOf(allocation), wallet = wallet(available = 30))
        every { walletLedgerService.apply(any()) } returns emptyList()
        every { revenueIssueRecorder.recordRecoveryRequired(capture(issues)) } just io.mockk.Runs
        every { walletLedgerEntryRepository.findAllByOperationKeyInForUpdate(any()) } returnsMany listOf(
            emptyList(),
            listOf(WalletLedgerEntryEntity(operationKey = "refund:reverse:item-1:1", availableDeltaMinor = -30))
        )

        service.reverseCompletedRefund("refund-1")
        every { walletRepository.findForUpdate(any(), any(), "USD") } returns wallet(available = 20)
        service.reverseCompletedRefund("refund-1")

        assertEquals(30, allocation.reversedMinor)
        assertEquals(20, issues.last().uncoveredMinor)
        verify(exactly = 1) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `partially reversed pending allocation debits pending even with unrelated available funds`() {
        val allocation = allocation(
            amount = 100,
            status = SettlementAllocationStatus.PARTIALLY_REVERSED,
            reversed = 10,
            bucket = SettlementAllocationBalanceBucket.PENDING
        )
        val mutations = slot<List<WalletMutation>>()
        arrange(
            refundAmount = 50,
            cumulativeAmount = 50,
            allocations = listOf(allocation),
            wallet = wallet(pending = 40, available = 500)
        )
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        service.reverseCompletedRefund("refund-1")

        assertEquals(-40, mutations.captured.single().pendingDelta)
        assertEquals(0, mutations.captured.single().availableDelta)
    }

    @Test
    fun `frozen funds are never consumed and produce a recovery issue`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.AVAILABLE)
        val issue = slot<RecoveryRequiredRevenueIssue>()
        arrange(refundAmount = 50, cumulativeAmount = 50, allocations = listOf(allocation), wallet = wallet(frozen = 50))
        every { revenueIssueRecorder.recordRecoveryRequired(capture(issue)) } just io.mockk.Runs

        service.reverseCompletedRefund("refund-1")

        assertEquals(0, allocation.reversedMinor)
        assertEquals(SettlementAllocationStatus.AVAILABLE, allocation.status)
        assertEquals(50, issue.captured.uncoveredMinor)
        verify(exactly = 0) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `over refund is rejected before ledger allocation or settlement writes`() {
        val allocation = allocation(amount = 100, status = SettlementAllocationStatus.PENDING)
        arrange(refundAmount = 101, cumulativeAmount = 101, allocations = listOf(allocation), wallet = wallet(pending = 100))

        val error = assertThrows<IllegalArgumentException> { service.reverseCompletedRefund("refund-1") }

        assertEquals("REFUND_AMOUNT_EXCEEDS_SETTLEMENT", error.message)
        verify(exactly = 0) {
            walletLedgerService.apply(any())
            allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>())
            settlementRepository.save(any())
        }
    }

    @Test
    fun `missing doctor allocation is rejected before writes`() {
        arrange(
            refundAmount = 50,
            cumulativeAmount = 50,
            allocations = listOf(allocation(amount = 100, owner = SettlementAllocationOwnerType.PLATFORM)),
            wallet = wallet(pending = 100)
        )

        val error = assertThrows<IllegalArgumentException> { service.reverseCompletedRefund("refund-1") }

        assertEquals("SETTLEMENT_DOCTOR_ALLOCATION_INVALID", error.message)
        assertNoWrites()
    }

    @Test
    fun `multiple doctor allocations are rejected before writes`() {
        arrange(
            refundAmount = 50,
            cumulativeAmount = 50,
            allocations = listOf(
                allocation(id = 1, amount = 50),
                allocation(id = 2, amount = 50)
            ),
            wallet = wallet(pending = 100)
        )

        val error = assertThrows<IllegalArgumentException> { service.reverseCompletedRefund("refund-1") }

        assertEquals("SETTLEMENT_DOCTOR_ALLOCATION_INVALID", error.message)
        assertNoWrites()
    }

    @Test
    fun `allocation total mismatch is rejected before writes`() {
        arrange(
            refundAmount = 50,
            cumulativeAmount = 50,
            allocations = listOf(allocation(amount = 99)),
            wallet = wallet(pending = 100)
        )

        val error = assertThrows<IllegalArgumentException> { service.reverseCompletedRefund("refund-1") }

        assertEquals("SETTLEMENT_ALLOCATION_TOTAL_MISMATCH", error.message)
        assertNoWrites()
    }

    private fun arrange(
        refundId: String = "refund-1",
        refundAmount: Long,
        cumulativeAmount: Long,
        allocations: List<SettlementAllocationEntity>,
        wallet: WalletEntity?
    ) {
        val refund = RefundEntity(
            id = refundId,
            orderId = "order-1",
            userId = "user-1",
            amount = BigDecimal("1.00"),
            reason = "test",
            status = "APPROVED"
        )
        every { refundRepository.findById(refundId) } returns java.util.Optional.of(refund)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(refundId) } returns listOf(
            RefundItemEntity("item-${refundId.removePrefix("refund-")}", refundId, "payment-1", "TEST", "USD", refundAmount, status = "SUCCEEDED")
        )
        every { refundItemRepository.sumCompletedAmountMinor("order-1") } returns cumulativeAmount
        val settlement = SettlementEntity(id = 91, orderId = "order-1", totalAmountMinor = 100, currency = "USD", status = "PENDING")
        every { settlementRepository.findByOrderIdForUpdate("order-1") } returns settlement
        every { allocationRepository.findAllBySettlementIdOrderByIdAscForUpdate(91) } returns allocations
        every { walletRepository.findForUpdate(any(), any(), "USD") } returns wallet
        every { walletLedgerEntryRepository.findAllByOperationKeyInForUpdate(any()) } returns emptyList()
        every { allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>()) } answers { firstArg() }
        every { settlementRepository.save(any()) } answers { firstArg() }
    }

    private fun allocation(
        id: Long = 1,
        amount: Long,
        owner: SettlementAllocationOwnerType = SettlementAllocationOwnerType.DOCTOR,
        status: SettlementAllocationStatus = SettlementAllocationStatus.PENDING,
        reversed: Long = 0,
        bucket: SettlementAllocationBalanceBucket = if (status == SettlementAllocationStatus.PENDING) {
            SettlementAllocationBalanceBucket.PENDING
        } else {
            SettlementAllocationBalanceBucket.AVAILABLE
        }
    ) = SettlementAllocationEntity(
        id = id,
        settlementId = 91,
        ownerType = owner,
        ownerId = "owner-$id",
        ownerName = "owner-$id",
        rate = BigDecimal.ZERO,
        amountMinor = amount,
        reversedMinor = reversed,
        balanceBucket = bucket,
        status = status
    )

    private fun wallet(pending: Long = 0, available: Long = 0, frozen: Long = 0): WalletEntity =
        WalletEntity(id = 1, ownerType = "DOCTOR", ownerId = "owner-1", currency = "USD").also {
            it.applyDeltas(pending, available, frozen)
        }

    private fun assertNoWrites() {
        verify(exactly = 0) {
            walletLedgerService.apply(any())
            allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>())
            settlementRepository.save(any())
        }
    }
}
