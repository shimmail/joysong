package com.joysong.server.wallet

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.wallet.controller.WalletController
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.dto.WalletLedgerDto
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import io.mockk.slot
import kotlin.reflect.full.memberProperties

class WalletControllerTest {
    @Test
    fun `professional wallet reads use actor scopes and never caller supplied owner ids`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val wallets = mockk<WalletRepository>()
        val ledgers = mockk<WalletLedgerEntryRepository>()
        every { access.actor(authentication) } returns ManagementActor(
            userId = "user-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR", "CONSULTANT"),
            doctorId = "doctor-1",
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = setOf("doctor-1")
        )
        every { access.walletScopes(any()) } answers { callOriginal() }
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) } returns listOf(
            WalletEntity(id = 1, ownerType = "DOCTOR", ownerId = "doctor-1", currency = "CNY")
        )
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("CONSULTANT", setOf("user-1")) } returns listOf(
            WalletEntity(id = 2, ownerType = "CONSULTANT", ownerId = "user-1", currency = "CNY")
        )
        val controller = WalletController(access, wallets, ledgers)

        val response = controller.myWallets(authentication)

        assertEquals(200, response.code)
        verify(exactly = 0) { wallets.findAllByOwnerTypeAndOwnerIdIn("INSTITUTION", any()) }
    }

    @Test
    fun `institution legal representative reads only visible institution wallets`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val wallets = mockk<WalletRepository>()
        val ledgers = mockk<WalletLedgerEntryRepository>()
        every { access.actor(authentication) } returns ManagementActor(
            userId = "legal-1",
            isAdmin = false,
            activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
            doctorId = null,
            managedInstitutionIds = setOf("institution-1"),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )
        every { access.walletScopes(any()) } answers { callOriginal() }
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("INSTITUTION", setOf("institution-1")) } returns emptyList()
        val controller = WalletController(access, wallets, ledgers)

        val response = controller.myWallets(authentication)

        assertEquals(200, response.code)
        verify(exactly = 1) { wallets.findAllByOwnerTypeAndOwnerIdIn("INSTITUTION", setOf("institution-1")) }
    }

    @Test
    fun `ledger returns only scoped wallet history with currency and bounded stable pagination`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val wallets = mockk<WalletRepository>()
        val ledgers = mockk<WalletLedgerEntryRepository>()
        val actor = ManagementActor(
            userId = "doctor-user", isAdmin = false, activeRoles = setOf("DOCTOR"), doctorId = "doctor-1",
            managedInstitutionIds = emptySet(), doctorInstitutionIds = emptySet(), manageableDoctorIds = setOf("doctor-1")
        )
        val wallet = WalletEntity(id = 11, ownerType = "DOCTOR", ownerId = "doctor-1", currency = "USD")
        val pageable = slot<Pageable>()
        every { access.actor(authentication) } returns actor
        every { access.walletScopes(any()) } answers { callOriginal() }
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) } returns listOf(wallet)
        every { ledgers.findAllByWalletIdInOrderByIdDesc(setOf(11), capture(pageable)) } returns PageImpl(listOf(
            WalletLedgerEntryEntity(id = 8, walletId = 11, entryType = "SETTLEMENT", sourceType = "SETTLEMENT", sourceId = "9")
        ))
        val controller = WalletController(access, wallets, ledgers)

        val response = controller.myLedger(authentication, page = -7, size = 1000)
        val body = response.data as Map<*, *>
        val item = (body["content"] as List<*>).single() as WalletLedgerDto

        assertEquals("USD", item.currency)
        assertEquals(0, pageable.captured.pageNumber)
        assertEquals(100, pageable.captured.pageSize)
        verify(exactly = 1) { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) }
    }

    @Test
    fun `wallet response DTOs contain no payout or bank secrets`() {
        val forbidden = listOf("bank", "beneficiary", "provider", "payout", "withdraw", "fx")
        val names = WalletLedgerDto::class.memberProperties.map { it.name.lowercase() }
        assertEquals(emptyList<String>(), names.filter { name -> forbidden.any(name::contains) })
    }
}
