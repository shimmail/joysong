package com.joysong.server.settlement.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "settlement_allocations")
class SettlementAllocationEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "settlement_id", nullable = false)
    val settlementId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    val ownerType: SettlementAllocationOwnerType = SettlementAllocationOwnerType.PLATFORM,

    @Column(name = "owner_id", nullable = false, length = 36)
    val ownerId: String = "",

    @Column(name = "owner_name", nullable = false, length = 200)
    val ownerName: String = "",

    @Column(name = "rate", nullable = false, precision = 5, scale = 2)
    val rate: BigDecimal = BigDecimal.ZERO,

    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long = 0,

    @Column(name = "reversed_minor", nullable = false)
    var reversedMinor: Long = 0,
        private set,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: SettlementAllocationStatus = SettlementAllocationStatus.PENDING,
        private set,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
) {
    fun markAvailable() {
        require(status == SettlementAllocationStatus.PENDING) { "仅待入账分账可变为可用" }
        status = SettlementAllocationStatus.AVAILABLE
    }

    fun reverse(amount: Long) {
        require(amount > 0) { "冲正金额必须大于 0" }
        require(status != SettlementAllocationStatus.REVERSED) { "分账已完全冲正" }
        require(amount <= amountMinor - reversedMinor) { "冲正金额超过可冲正金额" }

        reversedMinor += amount
        status = if (reversedMinor == amountMinor) {
            SettlementAllocationStatus.REVERSED
        } else {
            SettlementAllocationStatus.PARTIALLY_REVERSED
        }
    }
}

enum class SettlementAllocationOwnerType {
    PLATFORM,
    INSTITUTION,
    DOCTOR,
    CONSULTANT
}

enum class SettlementAllocationStatus {
    PENDING,
    AVAILABLE,
    PARTIALLY_REVERSED,
    REVERSED
}
