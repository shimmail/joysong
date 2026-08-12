package com.joysong.server.wallet.service

import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.wallet.dto.WalletLedgerItemDto
import com.joysong.server.wallet.dto.WalletLedgerPageDto
import com.joysong.server.wallet.dto.WalletOverviewDto
import com.joysong.server.wallet.dto.WalletViewDto
import com.joysong.server.wallet.entity.WalletEntity
import com.joysong.server.wallet.entity.WalletLedgerEntryEntity
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service

@Service
class WalletReadService(
    private val managementAccessService: ManagementAccessService,
    private val walletRepository: WalletRepository,
    private val walletLedgerEntryRepository: WalletLedgerEntryRepository,
    private val doctorRepository: DoctorRepository,
    private val userRepository: UserRepository,
    private val institutionRepository: InstitutionRepository
) {
    fun overview(actor: ManagementActor): WalletOverviewDto = WalletOverviewDto(wallets = visibleWallets(actor).map(::toView))

    fun ledger(actor: ManagementActor, walletId: Long, page: Int, size: Int): WalletLedgerPageDto {
        val wallet = visibleWallets(actor).firstOrNull { it.id == walletId }
            ?: throw AccessDeniedException("无权查看该钱包流水")
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        val result = walletLedgerEntryRepository.findAllByWalletIdOrderByCreatedAtDescIdDesc(
            wallet.id, PageRequest.of(safePage, safeSize)
        )
        return WalletLedgerPageDto(
            content = result.content.map { it.toLedgerItem(wallet.currency) },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            last = result.isLast
        )
    }

    private fun visibleWallets(actor: ManagementActor): List<WalletEntity> = managementAccessService.walletScopes(actor)
        .flatMap { scope -> walletRepository.findAllByOwnerTypeAndOwnerIdIn(scope.ownerType, scope.ownerIds) }
        .filter { it.currency == "USD" }.sortedWith(
        compareBy<WalletEntity>({ ownerTypeOrder(it.ownerType) }, { it.ownerId }, { it.id })
    )

    private fun toView(wallet: WalletEntity) = WalletViewDto(
        walletId = wallet.id,
        ownerType = wallet.ownerType,
        ownerId = wallet.ownerId,
        displayName = wallet.ownerType,
        ownerName = ownerName(wallet),
        pendingMinor = wallet.pendingMinor,
        availableMinor = wallet.availableMinor,
        frozenMinor = wallet.frozenMinor
    )

    private fun ownerName(wallet: WalletEntity): String = when (wallet.ownerType) {
        "DOCTOR" -> doctorRepository.findById(wallet.ownerId).orElse(null)?.name ?: wallet.ownerId
        "CONSULTANT" -> userRepository.findById(wallet.ownerId).orElse(null)?.nickname?.ifBlank { wallet.ownerId } ?: wallet.ownerId
        "INSTITUTION" -> institutionRepository.findById(wallet.ownerId).orElse(null)?.name ?: wallet.ownerId
        else -> wallet.ownerId
    }

    private fun WalletLedgerEntryEntity.toLedgerItem(currency: String) = WalletLedgerItemDto(
        id = id,
        walletId = walletId,
        entryType = entryType,
        title = entryType,
        description = "$sourceType:$sourceId",
        sourceType = sourceType,
        sourceId = sourceId,
        amountMinor = if (entryType == "RELEASE") 0L else
            sequenceOf(pendingDeltaMinor, availableDeltaMinor, frozenDeltaMinor).firstOrNull { it != 0L } ?: 0L,
        pendingAfterMinor = pendingBalanceMinor,
        availableAfterMinor = availableBalanceMinor,
        frozenAfterMinor = frozenBalanceMinor,
        currency = currency,
        createdAt = createdAt
    )

    private fun ownerTypeOrder(ownerType: String): Int = when (ownerType) {
        "DOCTOR" -> 0
        "CONSULTANT" -> 1
        "INSTITUTION" -> 2
        else -> 3
    }
}
