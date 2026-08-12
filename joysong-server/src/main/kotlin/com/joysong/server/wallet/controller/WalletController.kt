package com.joysong.server.wallet.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.wallet.service.WalletReadService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/wallets")
class WalletController(
    private val managementAccessService: ManagementAccessService,
    private val walletReadService: WalletReadService
) {
    @GetMapping("/me")
    fun myWallets(authentication: Authentication): BaseResponse<*> {
        return BaseResponse.success(walletReadService.overview(managementAccessService.actor(authentication)))
    }

    @GetMapping("/me/ledger")
    fun myLedger(
        authentication: Authentication,
        @RequestParam walletId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): BaseResponse<*> {
        return BaseResponse.success(walletReadService.ledger(managementAccessService.actor(authentication), walletId, page, size))
    }
}
