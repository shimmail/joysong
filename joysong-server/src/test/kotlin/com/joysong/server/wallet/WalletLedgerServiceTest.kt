package com.joysong.server.wallet

import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.wallet.service.WalletLedgerService
import com.joysong.server.wallet.service.WalletMutation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WalletLedgerServiceTest {
    private val walletRepository = mockk<WalletRepository>()
    private val ledgerRepository = mockk<WalletLedgerEntryRepository>()
    private val wallets = mutableMapOf<WalletKey, WalletEntity>()
    private val entries = mutableMapOf<String, WalletLedgerEntryEntity>()
    private val service = WalletLedgerService(walletRepository, ledgerRepository)

    init {
        every { ledgerRepository.findAllByOperationKeyIn(any()) } answers {
            firstArg<Collection<String>>().mapNotNull(entries::get)
        }
        every { walletRepository.findForUpdate(any(), any(), any()) } answers {
            wallets[WalletKey(firstArg(), secondArg(), thirdArg())]
        }
        every { walletRepository.save(any()) } answers {
            firstArg<WalletEntity>().also { wallets[WalletKey(it.ownerType, it.ownerId, it.currency)] = it }
        }
        every { ledgerRepository.save(any()) } answers {
            firstArg<WalletLedgerEntryEntity>().also { entries[it.operationKey] = it }
        }
    }

    @Test
    fun `replaying an operation key does not change balances twice`() {
        service.apply(listOf(credit("doctor-1", 500, "settlement:create:s1:a1")))
        service.apply(listOf(credit("doctor-1", 500, "settlement:create:s1:a1")))

        assertEquals(500, wallet("doctor-1").pendingMinor)
        verify(exactly = 1) { ledgerRepository.save(any()) }
    }

    @Test
    fun `wallets are locked in stable owner order`() {
        service.apply(listOf(credit("z", 1, "op-z"), credit("a", 1, "op-a")))

        verifyOrder {
            walletRepository.findForUpdate("DOCTOR", "a", "USD")
            walletRepository.findForUpdate("DOCTOR", "z", "USD")
        }
    }

    @Test
    fun `pending credit writes the projected balance to the ledger`() {
        val entry = service.apply(listOf(credit("doctor-1", 500, "op-credit"))).single()

        assertEquals(500, wallet("doctor-1").pendingMinor)
        assertEquals(500, entry.pendingDeltaMinor)
        assertEquals(0, entry.availableDeltaMinor)
        assertEquals(0, entry.frozenDeltaMinor)
    }

    @Test
    fun `pending-to-available release moves the balance between buckets`() {
        service.apply(listOf(credit("doctor-1", 500, "op-credit")))
        service.apply(listOf(mutation("doctor-1", pending = -500, available = 500, key = "op-release")))

        assertEquals(0, wallet("doctor-1").pendingMinor)
        assertEquals(500, wallet("doctor-1").availableMinor)
    }

    @Test
    fun `insufficient balances are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(listOf(mutation("doctor-1", pending = -1, key = "op-pending")))
        }
        service.apply(listOf(mutation("doctor-1", available = 1, key = "op-credit")))
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(listOf(mutation("doctor-1", available = -2, key = "op-available")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(listOf(mutation("doctor-1", frozen = -1, key = "op-frozen")))
        }
    }

    @Test
    fun `duplicate operation keys in one batch are rejected`() {
        val duplicate = credit("doctor-1", 1, "duplicate")

        assertThrows(IllegalArgumentException::class.java) {
            service.apply(listOf(duplicate, duplicate.copy(pendingDelta = 2)))
        }
    }

    @Test
    fun `blank identifiers currency and operation key are rejected`() {
        listOf(
            credit("", 1, "op-owner"),
            credit("doctor-1", 1, "op-currency").copy(currency = " "),
            credit("doctor-1", 1, " "),
            credit("doctor-1", 1, "op-source").copy(sourceId = "")
        ).forEach { mutation ->
            assertThrows(IllegalArgumentException::class.java) { service.apply(listOf(mutation)) }
        }
    }

    @Test
    fun `a later invalid mutation leaves repositories unchanged`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(listOf(
                credit("doctor-1", 100, "op-valid"),
                mutation("doctor-2", pending = -1, key = "op-invalid")
            ))
        }

        verify(exactly = 0) { walletRepository.save(any()) }
        verify(exactly = 0) { ledgerRepository.save(any()) }
    }

    private fun wallet(ownerId: String): WalletEntity = wallets.getValue(WalletKey("DOCTOR", ownerId, "USD"))

    private fun credit(ownerId: String, amount: Long, key: String): WalletMutation =
        mutation(ownerId, pending = amount, key = key)

    private fun mutation(
        ownerId: String,
        pending: Long = 0,
        available: Long = 0,
        frozen: Long = 0,
        key: String
    ) = WalletMutation(
        ownerType = "DOCTOR",
        ownerId = ownerId,
        currency = "USD",
        allocationId = 1,
        pendingDelta = pending,
        availableDelta = available,
        frozenDelta = frozen,
        entryType = "SETTLEMENT",
        sourceType = "SETTLEMENT",
        sourceId = "settlement-1",
        operationKey = key
    )

    private data class WalletKey(val ownerType: String, val ownerId: String, val currency: String)
}
