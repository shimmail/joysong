package com.joysong.server.wallet.service

import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class WalletMutation(
    val ownerType: String,
    val ownerId: String,
    val currency: String,
    val allocationId: Long?,
    val pendingDelta: Long,
    val availableDelta: Long,
    val frozenDelta: Long,
    val entryType: String,
    val sourceType: String,
    val sourceId: String,
    val operationKey: String
)

@Service
class WalletLedgerService(
    private val walletRepository: WalletRepository,
    private val ledgerRepository: WalletLedgerEntryRepository
) {
    @Transactional
    fun apply(mutations: List<WalletMutation>): List<WalletLedgerEntryEntity> {
        validate(mutations)
        if (mutations.isEmpty()) return emptyList()

        val existingByOperation = ledgerRepository.findAllByOperationKeyIn(mutations.map { it.operationKey })
            .associateBy { it.operationKey }
        val remaining = mutations.filterNot { existingByOperation.containsKey(it.operationKey) }
        if (remaining.isEmpty()) return mutations.map { existingByOperation.getValue(it.operationKey) }

        val ordered = remaining.sortedWith(compareBy<WalletMutation>({ it.currency }, { it.ownerType }, { it.ownerId }))
        val keys = ordered.map { WalletKey(it.currency, it.ownerType, it.ownerId) }.distinct()
        val wallets = keys.associateWith { key ->
            walletRepository.findForUpdate(key.ownerType, key.ownerId, key.currency)
                ?: WalletEntity(ownerType = key.ownerType, ownerId = key.ownerId, currency = key.currency)
        }

        val projected = wallets.mapValues { (_, wallet) ->
            WalletBalance(wallet.pendingMinor, wallet.availableMinor, wallet.frozenMinor)
        }.toMutableMap()
        ordered.forEach { mutation ->
            val key = WalletKey(mutation.currency, mutation.ownerType, mutation.ownerId)
            projected[key] = projected.getValue(key).plus(mutation)
        }

        ordered.forEach { mutation ->
            val key = WalletKey(mutation.currency, mutation.ownerType, mutation.ownerId)
            wallets.getValue(key).applyDeltas(
                mutation.pendingDelta,
                mutation.availableDelta,
                mutation.frozenDelta
            )
        }
        wallets.values.filter { it.id == 0L }.forEach(walletRepository::save)

        val createdByOperation = ordered.associate { mutation ->
            val key = WalletKey(mutation.currency, mutation.ownerType, mutation.ownerId)
            mutation.operationKey to ledgerRepository.save(
                WalletLedgerEntryEntity(
                    walletId = wallets.getValue(key).id,
                    allocationId = mutation.allocationId,
                    entryType = mutation.entryType,
                    pendingDeltaMinor = mutation.pendingDelta,
                    availableDeltaMinor = mutation.availableDelta,
                    frozenDeltaMinor = mutation.frozenDelta,
                    sourceType = mutation.sourceType,
                    sourceId = mutation.sourceId,
                    operationKey = mutation.operationKey
                )
            )
        }
        return mutations.map { mutation ->
            existingByOperation[mutation.operationKey] ?: createdByOperation.getValue(mutation.operationKey)
        }
    }

    private fun validate(mutations: List<WalletMutation>) {
        require(mutations.map { it.operationKey }.toSet().size == mutations.size) {
            "同一请求中的操作键必须唯一"
        }
        mutations.forEach { mutation ->
            require(mutation.ownerType.isNotBlank()) { "所有者类型不能为空" }
            require(mutation.ownerId.isNotBlank()) { "所有者 ID 不能为空" }
            require(mutation.currency.isNotBlank()) { "币种不能为空" }
            require(mutation.entryType.isNotBlank()) { "账本类型不能为空" }
            require(mutation.sourceType.isNotBlank()) { "来源类型不能为空" }
            require(mutation.sourceId.isNotBlank()) { "来源 ID 不能为空" }
            require(mutation.operationKey.isNotBlank()) { "操作键不能为空" }
        }
    }

    private data class WalletKey(val currency: String, val ownerType: String, val ownerId: String)

    private data class WalletBalance(
        val pendingMinor: Long,
        val availableMinor: Long,
        val frozenMinor: Long
    ) {
        fun plus(mutation: WalletMutation): WalletBalance {
            val pending = Math.addExact(pendingMinor, mutation.pendingDelta)
            val available = Math.addExact(availableMinor, mutation.availableDelta)
            val frozen = Math.addExact(frozenMinor, mutation.frozenDelta)
            require(pending >= 0 && available >= 0 && frozen >= 0) { "钱包余额不能为负数" }
            return WalletBalance(pending, available, frozen)
        }
    }
}
