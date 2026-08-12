package com.joysong.server.wallet.repository

import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WalletLedgerEntryRepository : JpaRepository<WalletLedgerEntryEntity, Long> {
    fun findAllByOperationKeyIn(operationKeys: Collection<String>): List<WalletLedgerEntryEntity>
    fun findAllByAllocationIdOrderByIdAsc(allocationId: Long): List<WalletLedgerEntryEntity>
    fun findAllByWalletIdOrderByIdAsc(walletId: Long): List<WalletLedgerEntryEntity>
    fun findAllByWalletIdInOrderByIdDesc(walletIds: Set<Long>, pageable: Pageable): Page<WalletLedgerEntryEntity>
    fun findAllByWalletIdOrderByCreatedAtDescIdDesc(walletId: Long, pageable: Pageable): Page<WalletLedgerEntryEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select entry from WalletLedgerEntryEntity entry where entry.operationKey in :operationKeys")
    fun findAllByOperationKeyInForUpdate(
        @Param("operationKeys") operationKeys: Collection<String>
    ): List<WalletLedgerEntryEntity>
}
