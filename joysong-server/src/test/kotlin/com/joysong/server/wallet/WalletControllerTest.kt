package com.joysong.server.wallet

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.wallet.controller.WalletController
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication

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
}
