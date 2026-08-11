package com.joysong.server.wallet.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.wallet.dto.WalletGroupDto
import com.joysong.server.wallet.dto.toDto
import com.joysong.server.wallet.dto.toSummaryDto
import com.joysong.server.wallet.repository.WalletLedgerEntryRepository
import com.joysong.server.wallet.repository.WalletRepository
import org.springframework.data.domain.PageRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/wallets")
class WalletController(
    private val managementAccessService: ManagementAccessService,
    private val walletRepository: WalletRepository,
    private val walletLedgerEntryRepository: WalletLedgerEntryRepository
) {
    @GetMapping("/me")
    fun myWallets(authentication: Authentication): BaseResponse<*> {
        val groups = managementAccessService.walletScopes(managementAccessService.actor(authentication)).flatMap { scope ->
            walletRepository.findAllByOwnerTypeAndOwnerIdIn(scope.ownerType, scope.ownerIds)
                .groupBy { it.ownerId }
                .map { (ownerId, wallets) ->
                    WalletGroupDto(scope.ownerType, ownerId, wallets.sortedBy { it.currency }.map { it.toSummaryDto() })
                }
        }.sortedWith(compareBy(WalletGroupDto::ownerType, WalletGroupDto::ownerId))
        return BaseResponse.success(groups)
    }

    @GetMapping("/me/ledger")
    fun myLedger(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        val scopedWallets = managementAccessService.walletScopes(managementAccessService.actor(authentication))
            .flatMap { walletRepository.findAllByOwnerTypeAndOwnerIdIn(it.ownerType, it.ownerIds) }
        val walletIds = scopedWallets.map { it.id }
            .toSet()
        val currenciesByWalletId = scopedWallets.associate { it.id to it.currency }
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        if (walletIds.isEmpty()) {
            return BaseResponse.success(mapOf("content" to emptyList<Any>(), "totalElements" to 0, "totalPages" to 0, "number" to safePage, "size" to safeSize))
        }
        val result = walletLedgerEntryRepository.findAllByWalletIdInOrderByIdDesc(walletIds, PageRequest.of(safePage, safeSize))
        return BaseResponse.success(mapOf(
            "content" to result.content.map { it.toDto(currenciesByWalletId.getValue(it.walletId)) },
            "totalElements" to result.totalElements,
            "totalPages" to result.totalPages,
            "number" to result.number,
            "size" to result.size
        ))
    }
}
