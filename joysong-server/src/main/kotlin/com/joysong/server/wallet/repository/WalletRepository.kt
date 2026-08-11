package com.joysong.server.wallet.repository

import com.joysong.server.wallet.entity.WalletEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WalletRepository : JpaRepository<WalletEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select wallet from WalletEntity wallet " +
            "where wallet.ownerType = :ownerType and wallet.ownerId = :ownerId and wallet.currency = :currency"
    )
    fun findForUpdate(
        @Param("ownerType") ownerType: String,
        @Param("ownerId") ownerId: String,
        @Param("currency") currency: String
    ): WalletEntity?
}
