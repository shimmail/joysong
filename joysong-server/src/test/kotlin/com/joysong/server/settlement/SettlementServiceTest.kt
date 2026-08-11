package com.joysong.server.settlement

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementAllocationBalanceBucket
import com.joysong.server.settlement.entity.SettlementAllocationOwnerType
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementAmountAllocator
import com.joysong.server.settlement.service.SettlementService
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

class SettlementServiceTest {
    @MockK private lateinit var settlementRepository: SettlementRepository
    @MockK private lateinit var allocationRepository: SettlementAllocationRepository
    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var paymentRepository: PaymentRepository
    @MockK private lateinit var refundItemRepository: RefundItemRepository
    @MockK private lateinit var configRepository: DoctorInstitutionProjectConfigRepository
    @MockK private lateinit var walletLedgerService: WalletLedgerService
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService

    private lateinit var service: SettlementService

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        service = SettlementService(
            settlementRepository,
            allocationRepository,
            orderRepository,
            paymentRepository,
            refundItemRepository,
            OrderSplitRatePolicy(OrderSplitProperties()),
            configRepository,
            SettlementAmountAllocator(),
            walletLedgerService,
            orderStatusLogService
        )
    }

    @Test
    fun `settlement uses paid net of completed refunds and credits four pending wallets`() {
        val order = completeOrder()
        val settlement = slot<SettlementEntity>()
        val allocations = slot<Iterable<SettlementAllocationEntity>>()
        val mutations = slot<List<WalletMutation>>()
        arrangeNewSettlement(order, 10_001, 1_234, settlement, allocations, mutations)

        service.saveSettlement(order.id)

        assertEquals(8_767, settlement.captured.totalAmountMinor)
        assertEquals(BigDecimal("87.67"), settlement.captured.totalAmount)
        assertEquals(4, allocations.captured.count())
        assertEquals(8_767, allocations.captured.sumOf { it.amountMinor })
        assertTrue(allocations.captured.all { it.balanceBucket == SettlementAllocationBalanceBucket.PENDING })
        assertEquals(
            setOf(
                SettlementAllocationOwnerType.PLATFORM,
                SettlementAllocationOwnerType.INSTITUTION,
                SettlementAllocationOwnerType.CONSULTANT,
                SettlementAllocationOwnerType.DOCTOR
            ),
            allocations.captured.map { it.ownerType }.toSet()
        )
        assertEquals(4, mutations.captured.size)
        assertEquals(allocations.captured.map { it.amountMinor }.toSet(), mutations.captured.map { it.pendingDelta }.toSet())
        assertEquals(
            setOf(
                "settlement:create:91:1",
                "settlement:create:91:2",
                "settlement:create:91:3",
                "settlement:create:91:4"
            ),
            mutations.captured.map { it.operationKey }.toSet()
        )
        mutations.captured.forEach {
            assertEquals(0, it.availableDelta)
            assertEquals(0, it.frozenDelta)
            assertEquals("SETTLEMENT", it.entryType)
            assertEquals("SETTLEMENT", it.sourceType)
            assertEquals("91", it.sourceId)
        }
    }

    @Test
    fun `duplicate invocation returns existing settlement without new money facts`() {
        val order = completeOrder()
        val existing = SettlementEntity(id = 9, orderId = order.id)
        every { orderRepository.findByIdForUpdate(order.id) } returns order
        every { settlementRepository.findByOrderId(order.id) } returns existing

        assertEquals(existing, service.saveSettlement(order.id))

        verify(exactly = 0) { paymentRepository.sumSucceededAmountMinor(any()) }
        verify(exactly = 0) { allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>()) }
        verify(exactly = 0) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `incomplete recipient snapshot fails before settlement allocation or wallet writes`() {
        val order = completeOrder().copy(consultantId = "", consultantName = "")
        every { orderRepository.findByIdForUpdate(order.id) } returns order
        every { settlementRepository.findByOrderId(order.id) } returns null

        assertThrows<IllegalStateException> { service.saveSettlement(order.id) }

        verify(exactly = 0) { paymentRepository.sumSucceededAmountMinor(any()) }
        verify(exactly = 0) { settlementRepository.saveAndFlush(any()) }
        verify(exactly = 0) { allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>()) }
        verify(exactly = 0) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `non-positive paid net fails before creating money facts`() {
        val order = completeOrder()
        every { orderRepository.findByIdForUpdate(order.id) } returns order
        every { settlementRepository.findByOrderId(order.id) } returns null
        every { paymentRepository.sumSucceededAmountMinor(order.id) } returns 1_000
        every { refundItemRepository.sumCompletedAmountMinor(order.id) } returns 1_000

        val error = assertThrows<IllegalArgumentException> { service.saveSettlement(order.id) }

        assertEquals("ORDER_NET_PAID_NOT_POSITIVE", error.message)
        verify(exactly = 0) { settlementRepository.saveAndFlush(any()) }
        verify(exactly = 0) { allocationRepository.saveAll(any<Iterable<SettlementAllocationEntity>>()) }
        verify(exactly = 0) { walletLedgerService.apply(any()) }
    }

    @Test
    fun `wallet ledger failure escapes the rollback transaction boundary`() {
        val order = completeOrder()
        val settlement = slot<SettlementEntity>()
        val allocations = slot<Iterable<SettlementAllocationEntity>>()
        val mutations = slot<List<WalletMutation>>()
        arrangeNewSettlement(order, 10_000, 0, settlement, allocations, mutations)
        every { walletLedgerService.apply(any()) } throws IllegalStateException("WALLET_LEDGER_FAILED")

        val error = assertThrows<IllegalStateException> { service.saveSettlement(order.id) }

        assertEquals("WALLET_LEDGER_FAILED", error.message)
        assertTrue(SettlementService::saveSettlement.annotations.any { it is Transactional })
    }

    private fun arrangeNewSettlement(
        order: OrderEntity,
        paid: Long,
        refunded: Long,
        settlement: io.mockk.CapturingSlot<SettlementEntity>,
        allocations: io.mockk.CapturingSlot<Iterable<SettlementAllocationEntity>>,
        mutations: io.mockk.CapturingSlot<List<WalletMutation>>
    ) {
        every { orderRepository.findByIdForUpdate(order.id) } returns order
        every { settlementRepository.findByOrderId(order.id) } returns null
        every { paymentRepository.sumSucceededAmountMinor(order.id) } returns paid
        every { refundItemRepository.sumCompletedAmountMinor(order.id) } returns refunded
        every {
            configRepository.findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
        } returns DoctorInstitutionProjectConfigEntity(
            doctorId = order.doctorId,
            institutionProjectId = order.institutionProjectId,
            institutionRate = BigDecimal("35.00"),
            commissionRate = BigDecimal("10.00")
        )
        every { settlementRepository.saveAndFlush(capture(settlement)) } answers {
            SettlementEntity(id = 91, orderId = order.id)
        }
        every { allocationRepository.saveAll(capture(allocations)) } answers {
            allocations.captured.mapIndexed { index, allocation ->
                SettlementAllocationEntity(
                    id = (index + 1).toLong(),
                    settlementId = allocation.settlementId,
                    ownerType = allocation.ownerType,
                    ownerId = allocation.ownerId,
                    ownerName = allocation.ownerName,
                    rate = allocation.rate,
                    amountMinor = allocation.amountMinor
                )
            }
        }
        every { walletLedgerService.apply(capture(mutations)) } returns emptyList()
    }

    private fun completeOrder() = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        institutionName = "机构",
        consultantId = "consultant-1",
        consultantName = "咨询师",
        doctorId = "doctor-1",
        doctorName = "医生",
        institutionId = "institution-1",
        institutionProjectId = "institution-project-1",
        price = BigDecimal("100.00"),
        status = OrderStatusEnum.PENDING_SETTLEMENT.value
    )
}
