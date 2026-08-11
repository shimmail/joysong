package com.joysong.server.settlement

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderScheduledTasks
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementAllocationStatus
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementReleaseService
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

class SettlementReleaseServiceTest {
    @MockK private lateinit var settlementRepository: SettlementRepository
    @MockK private lateinit var allocationRepository: SettlementAllocationRepository
    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var walletLedgerService: WalletLedgerService
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService
    @MockK private lateinit var orderService: OrderService
    @MockK private lateinit var schedulerReleaseService: SettlementReleaseService

    private lateinit var releaseService: SettlementReleaseService

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        releaseService = SettlementReleaseService(
            settlementRepository,
            allocationRepository,
            orderRepository,
            walletLedgerService,
            orderStatusLogService
        )
    }

    @Test
    fun `release moves remaining pending balances to available and settles the order`() {
        val now = LocalDateTime.of(2026, 8, 11, 3, 0)
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "PENDING")
        val allocations = listOf(allocation(1, 100), allocation(2, 250))
        val mutations = slot<List<WalletMutation>>()
        arrangeRelease(settlement, allocations, pendingSettlementOrder())
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        releaseService.release(settlement.id, now)

        assertEquals("AVAILABLE", settlement.status)
        assertEquals(now, settlement.updatedAt)
        assertTrue(allocations.all { it.status == SettlementAllocationStatus.AVAILABLE })
        assertEquals(
            listOf("settlement:release:91:1", "settlement:release:91:2"),
            mutations.captured.map { it.operationKey }
        )
        assertEquals(listOf(-100L, -250L), mutations.captured.map { it.pendingDelta })
        assertEquals(listOf(100L, 250L), mutations.captured.map { it.availableDelta })
        assertTrue(mutations.captured.all { it.entryType == "RELEASE" && it.frozenDelta == 0L })
        verify {
            orderRepository.save(match { it.status == OrderStatusEnum.SETTLED.value && it.updatedAt == now })
            orderStatusLogService.logTransition(
                "order-1",
                OrderStatusEnum.PENDING_SETTLEMENT.value,
                OrderStatusEnum.SETTLED.value,
                null,
                "SYSTEM",
                "结算到期自动结算"
            )
        }
    }

    @Test
    fun `repeated release of an available settlement writes nothing`() {
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "AVAILABLE")
        every { settlementRepository.findByIdForUpdate(91) } returns settlement

        releaseService.release(91, LocalDateTime.of(2026, 8, 11, 3, 0))

        verify(exactly = 0) {
            allocationRepository.findAllBySettlementIdOrderByIdAsc(any())
            walletLedgerService.apply(any())
            orderRepository.findByIdForUpdate(any())
            settlementRepository.save(any())
        }
    }

    @Test
    fun `release rejects an order outside pending settlement before ledger mutation`() {
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "PENDING")
        arrangeRelease(
            settlement,
            listOf(allocation(1, 100)),
            pendingSettlementOrder().copy(status = OrderStatusEnum.COMPLETED.value)
        )

        val error = assertThrows<IllegalStateException> {
            releaseService.release(91, LocalDateTime.of(2026, 8, 11, 3, 0))
        }

        assertEquals("订单状态不是PENDING_SETTLEMENT: order-1", error.message)
        verify(exactly = 0) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `release never moves the reversed portion of a partial allocation`() {
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "PENDING")
        val partial = allocation(1, 100, reversed = 30, status = SettlementAllocationStatus.PARTIALLY_REVERSED)
        val mutations = slot<List<WalletMutation>>()
        arrangeRelease(settlement, listOf(partial), pendingSettlementOrder())
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()

        releaseService.release(91, LocalDateTime.of(2026, 8, 11, 3, 0))

        assertEquals(70, mutations.captured.single().availableDelta)
        assertEquals(-70, mutations.captured.single().pendingDelta)
        assertEquals(SettlementAllocationStatus.PARTIALLY_REVERSED, partial.status)
    }

    @Test
    fun `release persists available bucket without losing partial reversal state`() {
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "PENDING")
        val partial = allocation(1, 100, reversed = 30, status = SettlementAllocationStatus.PARTIALLY_REVERSED)
        arrangeRelease(settlement, listOf(partial), pendingSettlementOrder())
        every { walletLedgerService.apply(any()) } returns emptyList()

        releaseService.release(91, LocalDateTime.of(2026, 8, 11, 3, 0))

        assertEquals(SettlementAllocationBalanceBucket.AVAILABLE, partial.balanceBucket)
        assertEquals(SettlementAllocationStatus.PARTIALLY_REVERSED, partial.status)
    }

    @Test
    fun `ledger failure leaves settlement allocation and order transitions unsaved`() {
        val settlement = SettlementEntity(id = 91, orderId = "order-1", status = "PENDING")
        val allocations = listOf(allocation(1, 100))
        arrangeRelease(settlement, allocations, pendingSettlementOrder())
        every { walletLedgerService.apply(any()) } throws IllegalStateException("WALLET_LEDGER_FAILED")

        val error = assertThrows<IllegalStateException> {
            releaseService.release(91, LocalDateTime.of(2026, 8, 11, 3, 0))
        }

        assertEquals("WALLET_LEDGER_FAILED", error.message)
        assertEquals("PENDING", settlement.status)
        assertEquals(SettlementAllocationStatus.PENDING, allocations.single().status)
        verify(exactly = 0) {
            allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>())
            settlementRepository.save(any())
            orderRepository.save(any())
            orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `scheduler continues with later due settlement after one release fails`() {
        val scheduler = OrderScheduledTasks(orderRepository, orderService, settlementRepository, schedulerReleaseService)
        every { settlementRepository.findDueSettlementIds("PENDING", any(), any()) } returns listOf(91, 92)
        every { schedulerReleaseService.release(91, any()) } throws IllegalStateException("first failed")
        every { schedulerReleaseService.release(92, any()) } just Runs

        scheduler.processSettlements()

        verify { schedulerReleaseService.release(92, any()) }
    }

    @Test
    fun `release is transactional on the service injected into the scheduler`() {
        val transaction = SettlementReleaseService::release.annotations.filterIsInstance<Transactional>().single()

        assertTrue(transaction.rollbackFor.contains(Exception::class))
        assertTrue(OrderScheduledTasks::class.java.declaredFields.any { it.type == SettlementReleaseService::class.java })
    }

    private fun arrangeRelease(
        settlement: SettlementEntity,
        allocations: List<SettlementAllocationEntity>,
        order: OrderEntity
    ) {
        every { settlementRepository.findByIdForUpdate(settlement.id) } returns settlement
        every { allocationRepository.findAllBySettlementIdOrderByIdAsc(settlement.id) } returns allocations
        every { orderRepository.findByIdForUpdate(order.id) } returns order
        every { allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>()) } answers { firstArg() }
        every { settlementRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } just Runs
    }

    private fun allocation(
        id: Long,
        amount: Long,
        reversed: Long = 0,
        status: SettlementAllocationStatus = SettlementAllocationStatus.PENDING
    ) = SettlementAllocationEntity(
        id = id,
        settlementId = 91,
        ownerType = SettlementAllocationOwnerType.DOCTOR,
        ownerId = "doctor-$id",
        ownerName = "医生$id",
        rate = BigDecimal("100.00"),
        amountMinor = amount,
        reversedMinor = reversed,
        status = status
    )

    private fun pendingSettlementOrder() = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("100.00"),
        status = OrderStatusEnum.PENDING_SETTLEMENT.value
    )
}
