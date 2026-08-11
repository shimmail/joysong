package com.joysong.server.wallet.repository

import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WalletLedgerEntryRepository : JpaRepository<WalletLedgerEntryEntity, Long> {
    fun findAllByOperationKeyIn(operationKeys: Collection<String>): List<WalletLedgerEntryEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from WalletLedgerEntryEntity entry where entry.operationKey in :operationKeys")
    fun findAllByOperationKeyInForUpdate(
        @Param("operationKeys") operationKeys: Collection<String>
    ): List<WalletLedgerEntryEntity>
}
