package com.joysong.server.wallet.dto

import com.joysong.server.reconciliation.entity.ReconciliationIssueEntity
import com.joysong.server.settlement.entity.SettlementAllocationEntity
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.payment.domain.Money
import java.time.LocalDateTime

data class MoneyAmountDto(val minor: Long, val currency: String)

data class WalletSummaryDto(
    val walletId: Long,
    val currency: String,
    val pending: MoneyAmountDto,
    val available: MoneyAmountDto,
    val frozen: MoneyAmountDto
)

data class WalletGroupDto(
    val ownerType: String,
    val ownerId: String,
    val wallets: List<WalletSummaryDto>
)

data class WalletLedgerDto(
    val id: Long,
    val walletId: Long,
    val currency: String,
    val allocationId: Long?,
    val entryType: String,
    val pendingDeltaMinor: Long,
    val availableDeltaMinor: Long,
    val frozenDeltaMinor: Long,
    val pendingBalanceMinor: Long,
    val availableBalanceMinor: Long,
    val frozenBalanceMinor: Long,
    val sourceType: String,
    val sourceId: String,
    val createdAt: LocalDateTime
)

data class SettlementSummaryDto(
    val settlementId: Long,
    val orderId: String,
    val currency: String,
    val total: MoneyAmountDto,
    val net: MoneyAmountDto,
    val state: String,
    val settlementDueAt: LocalDateTime?,
    val settlementCreatedAt: LocalDateTime,
    val releasedAt: LocalDateTime?
)

data class ConsumerSettlementDto(
    val settlementId: Long,
    val orderId: String,
    val currency: String,
    val grossTotalPaid: MoneyAmountDto,
    val netSettled: MoneyAmountDto,
    val state: String,
    val settlementDueAt: LocalDateTime?,
    val settlementCreatedAt: LocalDateTime,
    val releasedAt: LocalDateTime?
)

data class SettlementAllocationDto(
    val id: Long,
    val settlementId: Long,
    val ownerType: String,
    val ownerId: String,
    val amount: MoneyAmountDto,
    val reversedMinor: Long,
    val balanceBucket: String,
    val status: String,
    val createdAt: LocalDateTime
)

data class ReconciliationIssueDto(
    val id: Long,
    val issueType: String,
    val objectType: String,
    val objectId: String,
    val expectedMinor: Long,
    val actualMinor: Long,
    val currency: String?,
    val severity: String,
    val status: String,
    val occurrenceCount: Long,
    val firstDetectedAt: LocalDateTime,
    val lastDetectedAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
    val details: String?
)

fun WalletEntity.toSummaryDto() = WalletSummaryDto(
    walletId = id,
    currency = currency,
    pending = MoneyAmountDto(pendingMinor, currency),
    available = MoneyAmountDto(availableMinor, currency),
    frozen = MoneyAmountDto(frozenMinor, currency)
)

fun WalletLedgerEntryEntity.toDto(currency: String) = WalletLedgerDto(
    id, walletId, currency, allocationId, entryType, pendingDeltaMinor, availableDeltaMinor, frozenDeltaMinor,
    pendingBalanceMinor, availableBalanceMinor, frozenBalanceMinor, sourceType, sourceId, createdAt
)

fun SettlementEntity.toSummaryDto(dueAt: LocalDateTime?) = SettlementSummaryDto(
    settlementId = id,
    orderId = orderId,
    currency = currency,
    total = MoneyAmountDto(totalAmountMinor ?: Money.toMinor(totalAmount, currency), currency),
    net = MoneyAmountDto(totalAmountMinor ?: Money.toMinor(totalAmount, currency), currency),
    state = status,
    settlementDueAt = dueAt,
    settlementCreatedAt = createdAt,
    releasedAt = updatedAt.takeIf { status in setOf("AVAILABLE", "PARTIALLY_REVERSED", "REVERSED") }
)

fun SettlementEntity.toConsumerDto(dueAt: LocalDateTime?, grossPaidMinor: Long) = ConsumerSettlementDto(
    settlementId = id,
    orderId = orderId,
    currency = currency,
    grossTotalPaid = MoneyAmountDto(grossPaidMinor, currency),
    netSettled = MoneyAmountDto(totalAmountMinor ?: Money.toMinor(totalAmount, currency), currency),
    state = status,
    settlementDueAt = dueAt,
    settlementCreatedAt = createdAt,
    releasedAt = updatedAt.takeIf { status in setOf("AVAILABLE", "PARTIALLY_REVERSED", "REVERSED") }
)

fun SettlementAllocationEntity.toDto(currency: String) = SettlementAllocationDto(
    id, settlementId, ownerType.name, ownerId, MoneyAmountDto(amountMinor, currency), reversedMinor,
    balanceBucket.name, status.name, createdAt
)

fun ReconciliationIssueEntity.toDto() = ReconciliationIssueDto(
    id, issueType, objectType, objectId, expectedMinor, actualMinor, currency, severity, status,
    occurrenceCount, firstDetectedAt, lastDetectedAt, resolvedAt, details
)
