package com.joysong.server.user.deletion

import com.joysong.server.common.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/user/account-deletion")
class AccountDeletionController(
    private val stepUpService: AccountDeletionStepUpService,
    private val coordinator: AccountDeletionCoordinator,
) {
    @PostMapping("/preflight")
    fun preflight(authentication: Authentication): BaseResponse<AccountDeletionPreflightResponse> =
        BaseResponse.success(stepUpService.preflight(authentication.userId()))

    @PostMapping("/send-sms-code")
    fun sendSmsCode(
        authentication: Authentication,
        @Valid @RequestBody request: SendAccountDeletionSmsCodeRequest,
    ): BaseResponse<AccountDeletionSmsCodeResponse> =
        BaseResponse.success(stepUpService.sendSmsCode(authentication.userId(), request.requestId))

    @PostMapping("/step-up")
    fun stepUp(
        authentication: Authentication,
        @Valid @RequestBody request: AccountDeletionStepUpRequest,
    ): BaseResponse<AccountDeletionStepUpResponse> =
        BaseResponse.success(stepUpService.stepUp(authentication.userId(), request))

    @PostMapping("/confirm")
    fun confirm(
        authentication: Authentication?,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("X-Account-Deletion-Authorization") deletionAuthorization: String,
        @Valid @RequestBody request: ConfirmAccountDeletionRequest,
    ): ResponseEntity<BaseResponse<AccountDeletionConfirmResponse>> {
        val result = coordinator.confirm(
            authentication.authenticatedUserId(),
            idempotencyKey,
            deletionAuthorization,
            request,
        )
        return if (result.outcome == AccountDeletionTerminalOutcome.BLOCKED) {
            ResponseEntity.status(AccountDeletionErrorCode.BLOCKED.status).body(
                BaseResponse(
                    code = AccountDeletionErrorCode.BLOCKED.status.value(),
                    message = AccountDeletionErrorCode.BLOCKED.publicMessage,
                    errorCode = AccountDeletionErrorCode.BLOCKED.wireCode,
                    data = result,
                ),
            )
        } else {
            ResponseEntity.ok(BaseResponse.success(result))
        }
    }

    private fun Authentication.userId(): String = authenticatedUserId()
        ?: throw AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED)

    private fun Authentication?.authenticatedUserId(): String? =
        this
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?.principal
            ?.let { it as? String }
            ?.takeUnless { it == "anonymousUser" }
}
