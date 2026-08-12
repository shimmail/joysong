package com.joysong.server.wallet

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.wallet.controller.WalletController
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.dto.WalletOverviewDto
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication
import com.joysong.server.wallet.service.WalletReadService
import org.springframework.security.access.AccessDeniedException
import org.junit.jupiter.api.Assertions.assertThrows

class WalletControllerTest {
    @Test
    fun `overview derives an ordinary actor response from the authenticated principal`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val reader = mockk<WalletReadService>()
        every { access.actor(authentication) } returns ManagementActor(
            userId = "user-1",
            isAdmin = false,
            activeRoles = setOf("DOCTOR", "CONSULTANT"),
            doctorId = "doctor-1",
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = setOf("doctor-1")
        )
        every { reader.overview(any()) } returns WalletOverviewDto("USD", emptyList())
        val controller = WalletController(access, reader)

        val response = controller.myWallets(authentication)

        assertEquals(200, response.code)
        verify { reader.overview(match { it.userId == "user-1" }) }
    }

    @Test
    fun `ledger requires wallet id and never returns foreign financial data`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val reader = mockk<WalletReadService>()
        every { access.actor(authentication) } returns ManagementActor(
            userId = "legal-1",
            isAdmin = false,
            activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
            doctorId = null,
            managedInstitutionIds = setOf("institution-1"),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = emptySet()
        )
        every { reader.ledger(any(), 99, any(), any()) } throws AccessDeniedException("forbidden")
        val controller = WalletController(access, reader)

        assertThrows(AccessDeniedException::class.java) { controller.myLedger(authentication, 99, 0, 20) }
    }
}
