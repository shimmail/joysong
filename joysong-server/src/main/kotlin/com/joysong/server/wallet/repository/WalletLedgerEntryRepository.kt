package com.joysong.server.wallet.repository

import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WalletLedgerEntryRepository : JpaRepository<WalletLedgerEntryEntity, Long> {
    fun findAllByOperationKeyIn(operationKeys: Collection<String>): List<WalletLedgerEntryEntity>
}
