package com.joysong.server.wallet.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "wallet_ledger_entries")
class WalletLedgerEntryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "wallet_id", nullable = false)
    val walletId: Long = 0,

    @Column(name = "allocation_id")
    val allocationId: Long? = null,

    @Column(name = "entry_type", nullable = false, length = 30)
    val entryType: String = "",

    @Column(name = "pending_delta_minor", nullable = false)
    val pendingDeltaMinor: Long = 0,

    @Column(name = "available_delta_minor", nullable = false)
    val availableDeltaMinor: Long = 0,

    @Column(name = "frozen_delta_minor", nullable = false)
    val frozenDeltaMinor: Long = 0,

    @Column(name = "source_type", nullable = false, length = 30)
    val sourceType: String = "",

    @Column(name = "source_id", nullable = false, length = 100)
    val sourceId: String = "",

    @Column(name = "operation_key", nullable = false, length = 150, updatable = false)
    val operationKey: String = "",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
