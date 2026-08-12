package com.joysong.server.wallet.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(
    name = "wallets",
    uniqueConstraints = [UniqueConstraint(
        name = "uk_wallet_owner_currency",
        columnNames = ["owner_type", "owner_id", "currency"]
    )]
)
class WalletEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "owner_type", nullable = false, length = 20, updatable = false)
    val ownerType: String = "",

    @Column(name = "owner_id", nullable = false, length = 36, updatable = false)
    val ownerId: String = "",

    @Column(nullable = false, length = 3, updatable = false, columnDefinition = "CHAR(3)")
    val currency: String = "",

    @Version
    @Column(nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    @Column(name = "pending_minor", nullable = false)
    var pendingMinor: Long = 0
        private set

    @Column(name = "available_minor", nullable = false)
    var availableMinor: Long = 0
        private set

    @Column(name = "frozen_minor", nullable = false)
    var frozenMinor: Long = 0
        private set

    fun applyDeltas(pendingDelta: Long, availableDelta: Long, frozenDelta: Long) {
        val projectedPending = add(pendingMinor, pendingDelta)
        val projectedAvailable = add(availableMinor, availableDelta)
        val projectedFrozen = add(frozenMinor, frozenDelta)
        require(projectedPending >= 0 && projectedAvailable >= 0 && projectedFrozen >= 0) {
            "钱包余额不能为负数"
        }

        pendingMinor = projectedPending
        availableMinor = projectedAvailable
        frozenMinor = projectedFrozen
    }

    private fun add(balance: Long, delta: Long): Long = Math.addExact(balance, delta)
}
