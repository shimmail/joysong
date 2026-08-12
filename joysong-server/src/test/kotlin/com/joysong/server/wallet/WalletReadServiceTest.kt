package com.joysong.server.wallet

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.WalletOwnerScope
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.wallet.dto.WalletLedgerItemDto
import com.joysong.server.wallet.dto.WalletViewDto
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import com.joysong.server.wallet.service.WalletReadService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.security.access.AccessDeniedException
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import kotlin.reflect.full.memberProperties

class WalletReadServiceTest {
    private val wallets = mockk<WalletRepository>()
    private val ledgers = mockk<WalletLedgerEntryRepository>()
    private val access = mockk<ManagementAccessService>()
    private val doctors = mockk<DoctorRepository>()
    private val users = mockk<UserRepository>()
    private val institutions = mockk<InstitutionRepository>()
    private val service = WalletReadService(access, wallets, ledgers, doctors, users, institutions)

    @Test
    fun `ordinary actor receives an empty USD overview`() {
        val actor = actor()
        every { access.walletScopes(actor) } returns emptyList()

        val overview = service.overview(actor)

        assertEquals("USD", overview.currency)
        assertEquals(emptyList<WalletViewDto>(), overview.wallets)
    }

    @Test
    fun `three identities and multiple institutions remain independent with resolved names`() {
        val actor = actor(
            userId = "user-1",
            roles = setOf("DOCTOR", "CONSULTANT", "INSTITUTION_LEGAL_REPRESENTATIVE"),
            doctorId = "doctor-1",
            institutions = setOf("institution-2", "institution-1")
        )
        every { access.walletScopes(actor) } returns listOf(
            WalletOwnerScope("DOCTOR", setOf("doctor-1")),
            WalletOwnerScope("CONSULTANT", setOf("user-1")),
            WalletOwnerScope("INSTITUTION", setOf("institution-1", "institution-2"))
        )
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) } returns listOf(wallet(1, "DOCTOR", "doctor-1"))
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("CONSULTANT", setOf("user-1")) } returns listOf(wallet(2, "CONSULTANT", "user-1"))
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("INSTITUTION", setOf("institution-1", "institution-2")) } returns listOf(
            wallet(4, "INSTITUTION", "institution-2"), wallet(3, "INSTITUTION", "institution-1")
        )
        every { doctors.findById("doctor-1") } returns java.util.Optional.of(DoctorEntity("doctor-1", "张医生"))
        every { users.findById("user-1") } returns java.util.Optional.of(UserEntity("user-1", passwordHash = "hash", nickname = "李顾问"))
        every { institutions.findById("institution-1") } returns java.util.Optional.of(InstitutionEntity("institution-1", "娇颜颂一院"))
        every { institutions.findById("institution-2") } returns java.util.Optional.of(InstitutionEntity("institution-2", "娇颜颂二院"))

        val overview = service.overview(actor)

        assertEquals(listOf("DOCTOR", "CONSULTANT", "INSTITUTION", "INSTITUTION"), overview.wallets.map { it.ownerType })
        assertEquals(listOf("张医生", "李顾问", "娇颜颂一院", "娇颜颂二院"), overview.wallets.map { it.ownerName })
        assertEquals(listOf("医生钱包", "顾问钱包", "机构钱包", "机构钱包"), overview.wallets.map { it.displayName })
        assertEquals(listOf(1L, 2L, 3L, 4L), overview.wallets.map { it.walletId })
    }

    @Test
    fun `ledger rejects foreign wallet before reading financial data`() {
        val actor = actor(doctorId = "doctor-1", roles = setOf("DOCTOR"))
        every { access.walletScopes(actor) } returns listOf(WalletOwnerScope("DOCTOR", setOf("doctor-1")))
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) } returns listOf(wallet(11, "DOCTOR", "doctor-1"))

        assertThrows(AccessDeniedException::class.java) { service.ledger(actor, 99, 0, 20) }
    }

    @Test
    fun `ledger exposes signed integer minors and newest stable page`() {
        val actor = actor(doctorId = "doctor-1", roles = setOf("DOCTOR"))
        every { access.walletScopes(actor) } returns listOf(WalletOwnerScope("DOCTOR", setOf("doctor-1")))
        every { wallets.findAllByOwnerTypeAndOwnerIdIn("DOCTOR", setOf("doctor-1")) } returns listOf(wallet(11, "DOCTOR", "doctor-1"))
        every { ledgers.findAllByWalletIdOrderByCreatedAtDescIdDesc(11, any()) } returns PageImpl(listOf(
            WalletLedgerEntryEntity(id = 8, walletId = 11, entryType = "REFUND_REVERSAL", availableDeltaMinor = -1200, availableBalanceMinor = 8800, sourceType = "ORDER", sourceId = "JS1", createdAt = LocalDateTime.of(2026, 8, 11, 10, 30))
        ))

        val page = service.ledger(actor, 11, -1, 101)
        val item = page.content.single()

        assertEquals(-1200, item.amountMinor)
        assertEquals("USD", item.currency)
        verify { ledgers.findAllByWalletIdOrderByCreatedAtDescIdDesc(11, match { it.pageNumber == 0 && it.pageSize == 100 }) }
        assertEquals(emptyList<String>(), WalletLedgerItemDto::class.memberProperties.map { it.name.lowercase() }
            .filter { name -> listOf("provider", "payout", "bank", "beneficiary", "fx").any(name::contains) })
    }

    @Test
    fun `wallet read DTOs serialize only flat integer money fields`() {
        val json = ObjectMapper().findAndRegisterModules().writeValueAsString(
            com.joysong.server.wallet.dto.WalletOverviewDto(
                wallets = listOf(
                    WalletViewDto(1, "DOCTOR", "doctor-1", "医生钱包", "张医生", 1200, 8800, 0)
                )
            )
        )

        assertEquals(true, json.contains("\"availableMinor\":8800"))
        assertEquals(false, listOf("provider", "payout", "bank", "beneficiary", "fx").any { json.contains(it, ignoreCase = true) })
    }

    private fun actor(
        userId: String = "ordinary-1",
        roles: Set<String> = emptySet(),
        doctorId: String? = null,
        institutions: Set<String> = emptySet()
    ) = ManagementActor(userId, false, roles, doctorId, institutions, emptySet(), doctorId?.let(::setOf) ?: emptySet())

    private fun wallet(id: Long, ownerType: String, ownerId: String) =
        WalletEntity(id = id, ownerType = ownerType, ownerId = ownerId, currency = "USD").also {
            it.applyDeltas(1200, 8800, 0)
        }
}
