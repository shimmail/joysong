package com.joysong.server.admin.controller

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.repository.SettlementAllocationRepository
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.reconciliation.repository.ReconciliationIssueRepository
import com.joysong.server.reconciliation.entity.ReconciliationIssueEntity
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.dto.ConsumerSettlementDto
import com.joysong.server.wallet.dto.ReconciliationIssueDto
import com.joysong.server.wallet.dto.SettlementAllocationDto
import com.joysong.server.wallet.dto.SettlementSummaryDto
import com.joysong.server.wallet.dto.WalletLedgerDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.Authentication
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.util.Optional
import kotlin.reflect.full.memberProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

class AdminOrderControllerTest {
    @Test
    fun `admin config saves positive medical list price and rejects zero`() {
        val authentication = mockk<Authentication>()
        val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
        val accessService = mockk<ManagementAccessService>()
        every { accessService.actor(authentication) } returns adminActor()
        every { accessService.requireSplitConfig(any(), any(), any()) } returns Unit
        every { configRepository.findByDoctorIdAndInstitutionProjectId("doctor-1", "project-1") } returns null
        every { configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeleted("doctor-1", "project-1") } returns null
        every { configRepository.save(any()) } answers { firstArg() }
        val controller = AdminOrderController(
            mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), configRepository, accessService,
            OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") })
        )

        controller.upsertConfig(authentication, UpsertConfigRequest(
            doctorId = "doctor-1", institutionProjectId = "project-1", consultationFee = BigDecimal.ZERO,
            commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00"),
            medicalListPrice = BigDecimal("1000.00")
        ))

        verify(exactly = 1) { configRepository.save(match { it.medicalListPrice == BigDecimal("1000.00") }) }
        val error = assertThrows<IllegalArgumentException> {
            controller.upsertConfig(authentication, UpsertConfigRequest(
                doctorId = "doctor-1", institutionProjectId = "project-1", consultationFee = BigDecimal.ZERO,
                commissionRate = BigDecimal("10.00"), institutionRate = BigDecimal("40.00"),
                medicalListPrice = BigDecimal.ZERO
            ))
        }
        assertEquals("医疗套餐优惠前金额必须大于 0", error.message)
    }

    @Test
    fun `admin config save rejects rates exceeding the shared split policy before persistence`() {
        val authentication = mockk<Authentication>()
        val orderService = mockk<OrderService>()
        val orderStatusLogService = mockk<OrderStatusLogService>()
        val settlementRepository = mockk<SettlementRepository>()
        val settlementAllocationRepository = mockk<SettlementAllocationRepository>()
        val walletLedgerEntryRepository = mockk<WalletLedgerEntryRepository>()
        val walletRepository = mockk<WalletRepository>()
        val reconciliationIssueRepository = mockk<ReconciliationIssueRepository>()
        val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
        val accessService = mockk<ManagementAccessService>()
        every { accessService.actor(authentication) } returns adminActor()
        val policy = OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal("40.00")
        })
        val controller = AdminOrderController(
            orderService,
            orderStatusLogService,
            settlementRepository,
            settlementAllocationRepository,
            walletLedgerEntryRepository,
            walletRepository,
            reconciliationIssueRepository,
            configRepository,
            accessService,
            policy
        )

        val request = UpsertConfigRequest(
            doctorId = "doctor-1",
            institutionProjectId = "project-1",
            consultationFee = BigDecimal.ZERO,
            commissionRate = BigDecimal("20.01"),
            institutionRate = BigDecimal("40.00")
        )

        val error = assertThrows<IllegalArgumentException> {
            controller.upsertConfig(authentication, request)
        }

        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
        verify(exactly = 0) { configRepository.save(any()) }
    }

    @Test
    fun `admin read endpoints return safe issue settlement allocation and ledger details`() {
        val orderService = mockk<OrderService>()
        val logs = mockk<OrderStatusLogService>()
        val settlements = mockk<SettlementRepository>()
        val allocations = mockk<SettlementAllocationRepository>()
        val ledgers = mockk<WalletLedgerEntryRepository>()
        val wallets = mockk<WalletRepository>()
        val issues = mockk<ReconciliationIssueRepository>()
        val allocationPageable = slot<Pageable>()
        val controller = AdminOrderController(
            orderService, logs, settlements, allocations, ledgers, wallets, issues,
            mockk<DoctorInstitutionProjectConfigRepository>(), mockk<ManagementAccessService>(),
            OrderSplitRatePolicy(OrderSplitProperties())
        )
        val settlement = SettlementEntity(
            id = 7, orderId = "order-1", currency = "USD", totalAmount = BigDecimal("8.00"), totalAmountMinor = 800
        )
        every { settlements.findById(7) } returns Optional.of(settlement)
        every { allocations.findAllBySettlementIdOrderByIdAsc(7, capture(allocationPageable)) } returns PageImpl(listOf(
            SettlementAllocationEntity(id = 2, settlementId = 7, amountMinor = 100),
            SettlementAllocationEntity(id = 9, settlementId = 7, amountMinor = 700)
        ))
        val entry = WalletLedgerEntryEntity(id = 3, walletId = 4, entryType = "SETTLEMENT", sourceType = "SETTLEMENT", sourceId = "7")
        every { ledgers.findById(3) } returns Optional.of(entry)
        every { wallets.findById(4) } returns Optional.of(WalletEntity(id = 4, ownerType = "DOCTOR", ownerId = "doctor-1", currency = "USD"))
        val issue = ReconciliationIssueEntity(id = 6, issueType = "MISMATCH", objectType = "ORDER", objectId = "order-1")
        every { issues.findById(6) } returns Optional.of(issue)

        val settlementResult = controller.getSettlement(7).data as SettlementSummaryDto
        val allocationsResult = controller.getSettlementAllocations(7, page = -1, size = 1000).data as Map<*, *>
        val ledgerResult = controller.getLedgerEntry(3).data as WalletLedgerDto
        val issueResult = controller.getReconciliationIssue(6).data as ReconciliationIssueDto

        assertEquals(800, settlementResult.total.minor)
        assertEquals(listOf(2L, 9L), (allocationsResult["content"] as List<*>).map { (it as SettlementAllocationDto).id })
        assertEquals(0, allocationPageable.captured.pageNumber)
        assertEquals(100, allocationPageable.captured.pageSize)
        assertEquals("USD", ledgerResult.currency)
        assertEquals("MISMATCH", issueResult.issueType)
    }

    @Test
    fun `read DTOs omit provider and payout secrets`() {
        val forbidden = listOf("bank", "beneficiary", "provider", "payout", "withdraw", "fx")
        listOf(
            ConsumerSettlementDto::class,
            SettlementSummaryDto::class,
            SettlementAllocationDto::class,
            WalletLedgerDto::class,
            ReconciliationIssueDto::class
        ).forEach { type ->
            assertTrue(type.memberProperties.none { property -> forbidden.any(property.name.lowercase()::contains) })
        }
        val json = jacksonObjectMapper().registerModule(JavaTimeModule()).writeValueAsString(
            WalletLedgerDto(1, 2, "USD", null, "SETTLEMENT", 0, 0, 0, 0, 0, 0, "SETTLEMENT", "3", java.time.LocalDateTime.now())
        ).lowercase()
        assertTrue(forbidden.none(json::contains))
    }

    private fun adminActor() = ManagementActor(
        userId = "admin-1",
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )
}
