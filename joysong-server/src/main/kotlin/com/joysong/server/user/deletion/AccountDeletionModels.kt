package com.joysong.server.user.deletion

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

const val ACCOUNT_DELETION_POLICY_VERSION = "2026-08-28"

enum class AccountDeletionStepUpMethod { SMS, GOOGLE, NONE }

enum class AccountDeletionRequestStatus {
    PREFLIGHTED,
    PREFLIGHT_BLOCKED,
    CODE_SENT,
    AUTHORIZED,
    COMPLETED,
    BLOCKED,
}

enum class AccountDeletionTerminalOutcome { ERASED, BLOCKED }

data class AccountDeletionBlocker(
    val type: String,
    val count: Long,
    val action: String,
)

data class AccountDeletionPreflightResponse(
    val requestId: String,
    val eligible: Boolean,
    val stepUpMethod: AccountDeletionStepUpMethod?,
    val maskedCredential: String?,
    val policyVersion: String,
    val blockers: List<AccountDeletionBlocker>,
)

data class SendAccountDeletionSmsCodeRequest(
    @field:NotBlank val requestId: String,
)

data class AccountDeletionSmsCodeResponse(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
)

data class AccountDeletionStepUpRequest(
    @field:NotBlank val requestId: String,
    @field:Size(max = 16) val code: String? = null,
    @field:Size(max = 16_384) val googleIdToken: String? = null,
)

data class AccountDeletionStepUpResponse(
    val requestId: String,
    val deletionAuthorization: String,
    val expiresInSeconds: Long,
)

data class ConfirmAccountDeletionRequest(
    @field:NotBlank val requestId: String,
    @field:NotBlank val policyVersion: String,
    @field:NotBlank val confirmation: String,
)

data class AccountDeletionConfirmResponse(
    val requestId: String,
    val outcome: AccountDeletionTerminalOutcome,
    val completedAt: LocalDateTime,
    val blockers: List<AccountDeletionBlocker> = emptyList(),
)

data class AccountDeletionRequestRecord(
    val id: String,
    val userId: String,
    val policyVersion: String,
    val status: AccountDeletionRequestStatus,
    val stepUpMethod: AccountDeletionStepUpMethod,
    val verificationCodeHash: String?,
    val verificationAttemptCount: Int,
    val verificationMaxAttempts: Int,
    val verificationExpiresAt: LocalDateTime?,
    val resendAvailableAt: LocalDateTime?,
    val authorizationHash: String?,
    val authorizationExpiresAt: LocalDateTime?,
    val authorizationConsumedAt: LocalDateTime?,
    val idempotencyKeyHash: String?,
    val requestExpiresAt: LocalDateTime,
    val terminalOutcome: AccountDeletionTerminalOutcome?,
    val terminalResultJson: String?,
    val completedAt: LocalDateTime?,
)

enum class AccountDeletionErrorCode(
    val status: HttpStatus,
    val wireCode: String,
    val publicMessage: String,
) {
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_DISABLED", "账号注销功能暂不可用"),
    BLOCKER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_BLOCKER_UNAVAILABLE", "账号注销检查服务暂不可用"),
    REQUEST_INVALID(HttpStatus.BAD_REQUEST, "ACCOUNT_DELETION_REQUEST_FAILED", "注销请求无效"),
    VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "ACCOUNT_DELETION_VERIFICATION_FAILED", "二次验证失败"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "ACCOUNT_DELETION_REQUEST_FAILED", "请先登录"),
    BLOCKED(HttpStatus.CONFLICT, "ACCOUNT_DELETION_BLOCKED", "账号当前不满足注销条件"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "ACCOUNT_DELETION_IDEMPOTENCY_CONFLICT", "幂等请求冲突"),
    AUTHORIZATION_EXPIRED(HttpStatus.GONE, "ACCOUNT_DELETION_AUTHORIZATION_EXPIRED", "注销授权已过期或已使用"),
    SMS_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "ACCOUNT_DELETION_SMS_RATE_LIMITED", "请稍后再获取验证码"),
    SMS_DELIVERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_DELETION_SMS_DELIVERY_UNAVAILABLE", "短信服务暂不可用"),
    LEGACY_ENDPOINT_RETIRED(HttpStatus.GONE, "ACCOUNT_DELETION_LEGACY_ENDPOINT_RETIRED", "旧账号注销接口已停用"),
}

class AccountDeletionException(
    val errorCode: AccountDeletionErrorCode,
    message: String = errorCode.publicMessage,
    val blockers: List<AccountDeletionBlocker> = emptyList(),
) : RuntimeException(message)

data class VerifiedGoogleIdentity(
    val issuer: String,
    val audience: String,
    val email: String,
    val emailVerified: Boolean,
)
