package com.joysong.server.user.deletion

import com.joysong.server.auth.service.AliyunSmsService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

@Service
class AccountDeletionStepUpService(
    private val availability: AccountDeletionAvailability,
    private val properties: AccountDeletionProperties,
    private val lifecycleGuard: AccountLifecycleGuard,
    private val requestStore: AccountDeletionRequestStore,
    private val localBlockerService: LocalAccountDeletionBlockerService,
    private val commerceBlockerPort: AccountDeletionBlockerPort,
    private val smsService: AliyunSmsService,
    private val googleVerifier: GoogleAccountDeletionVerifier,
    private val crypto: AccountDeletionCrypto,
    private val clock: Clock,
    private val metrics: AccountDeletionMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun preflight(userId: String): AccountDeletionPreflightResponse {
        availability.requireEnabled()
        val user = lifecycleGuard.requireActiveForWrite(userId)
        val blockers = localBlockerService.evaluate(user).toMutableList()
        blockers += evaluateCommerce(userId)
        val (method, maskedCredential) = chooseStepUp(user)
        if (method == AccountDeletionStepUpMethod.NONE) {
            blockers += AccountDeletionBlocker("NO_STEP_UP_CREDENTIAL", 1, "CONTACT_SUPPORT")
        }
        val eligible = blockers.isEmpty()
        val now = now()
        val reusableRequest = if (eligible) {
            requestStore.findReusableForUserForUpdate(userId, method, now)
                ?.takeIf {
                    it.stepUpMethod == method &&
                        it.isReusableAt(userId, now) &&
                        maskedCredential(user, it.stepUpMethod) != null
                }
        } else {
            null
        }
        val requestId = reusableRequest?.id ?: UUID.randomUUID().toString()
        val responseMethod = reusableRequest?.stepUpMethod ?: method
        val responseCredential = maskedCredential(user, responseMethod) ?: maskedCredential
        if (reusableRequest == null) {
            requestStore.insert(
                AccountDeletionRequestRecord(
                    id = requestId,
                    userId = userId,
                    policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
                    status = if (eligible) AccountDeletionRequestStatus.PREFLIGHTED else AccountDeletionRequestStatus.PREFLIGHT_BLOCKED,
                    stepUpMethod = method,
                    verificationCodeHash = null,
                    verificationAttemptCount = 0,
                    verificationMaxAttempts = MAX_VERIFICATION_ATTEMPTS,
                    verificationExpiresAt = null,
                    resendAvailableAt = null,
                    authorizationHash = null,
                    authorizationExpiresAt = null,
                    authorizationConsumedAt = null,
                    idempotencyKeyHash = null,
                    requestExpiresAt = now.plusSeconds(properties.requestTtlSeconds),
                    terminalOutcome = null,
                    terminalResultJson = null,
                    completedAt = null,
                ),
            )
        }
        metrics.count("preflight", if (eligible) "NONE" else AccountDeletionErrorCode.BLOCKED.wireCode)
        if (!eligible) metrics.count("blocked", AccountDeletionErrorCode.BLOCKED.wireCode)
        logger.info("Account deletion preflight completed userId={} requestId={}", userId, requestId)
        return AccountDeletionPreflightResponse(
            requestId = requestId,
            eligible = eligible,
            stepUpMethod = responseMethod.takeUnless { it == AccountDeletionStepUpMethod.NONE },
            maskedCredential = responseCredential,
            policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
            blockers = blockers,
        )
    }

    @Transactional
    fun sendSmsCode(userId: String, requestId: String): AccountDeletionSmsCodeResponse {
        availability.requireEnabled()
        val request = requireUsableRequest(requestStore.findForUpdate(requestId), userId)
        val user = requireActive(userId)
        if (request.stepUpMethod != AccountDeletionStepUpMethod.SMS ||
            request.status !in setOf(AccountDeletionRequestStatus.PREFLIGHTED, AccountDeletionRequestStatus.CODE_SENT)
        ) invalidRequest()
        val now = now()
        if (request.resendAvailableAt?.isAfter(now) == true) {
            throw AccountDeletionException(AccountDeletionErrorCode.SMS_RATE_LIMITED)
        }
        if (request.verificationAttemptCount >= request.verificationMaxAttempts) verificationFailed()
        val phone = user.phone?.takeIf(String::isNotBlank) ?: invalidRequest()
        val developmentCode = availability.developmentFixedSmsCode()
        val code = developmentCode ?: crypto.randomSmsCode()
        requestStore.storeSmsChallenge(
            requestId,
            crypto.hash("$phone:$code"),
            now.plusSeconds(properties.smsCodeTtlSeconds),
            now.plusSeconds(properties.smsResendSeconds),
        )
        if (developmentCode == null && !smsService.sendVerificationCode(phone, code)) {
            throw AccountDeletionException(AccountDeletionErrorCode.SMS_DELIVERY_UNAVAILABLE)
        }
        logger.info("Account deletion SMS challenge sent userId={} requestId={}", userId, requestId)
        return AccountDeletionSmsCodeResponse(
            requestId = requestId,
            expiresInSeconds = properties.smsCodeTtlSeconds,
            resendAfterSeconds = properties.smsResendSeconds,
        )
    }

    @Transactional(noRollbackFor = [AccountDeletionException::class])
    fun stepUp(userId: String, command: AccountDeletionStepUpRequest): AccountDeletionStepUpResponse {
        availability.requireEnabled()
        val request = requireUsableRequest(requestStore.findForUpdate(command.requestId), userId)
        val user = requireActive(userId)
        if (request.status !in setOf(AccountDeletionRequestStatus.PREFLIGHTED, AccountDeletionRequestStatus.CODE_SENT)) {
            throw AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED)
        }
        when (request.stepUpMethod) {
            AccountDeletionStepUpMethod.SMS -> verifySms(request, user, command.code)
            AccountDeletionStepUpMethod.GOOGLE -> verifyGoogle(user, command.googleIdToken)
            AccountDeletionStepUpMethod.NONE -> invalidRequest()
        }
        val rawAuthorization = crypto.randomAuthorization()
        requestStore.storeAuthorization(
            request.id,
            crypto.hash(rawAuthorization),
            now().plusSeconds(properties.authorizationTtlSeconds),
        )
        logger.info("Account deletion step-up completed userId={} requestId={}", userId, request.id)
        return AccountDeletionStepUpResponse(
            requestId = request.id,
            deletionAuthorization = rawAuthorization,
            expiresInSeconds = properties.authorizationTtlSeconds,
        )
    }

    private fun verifySms(request: AccountDeletionRequestRecord, user: UserEntity, code: String?) {
        if (request.verificationAttemptCount >= request.verificationMaxAttempts) {
            metrics.count("step_up_failed", AccountDeletionErrorCode.VERIFICATION_FAILED.wireCode)
            verificationFailed()
        }
        val attempt = request.verificationAttemptCount + 1
        requestStore.storeVerificationAttempt(request.id, attempt)
        val phone = user.phone?.takeIf(String::isNotBlank)
        val verificationCode = code?.takeIf(SMS_CODE::matches)
        val notExpired = request.verificationExpiresAt?.isAfter(now()) == true
        val validHash = verificationCode != null &&
            phone != null &&
            crypto.matches("$phone:$verificationCode", request.verificationCodeHash)
        if (attempt > request.verificationMaxAttempts || !notExpired || !validHash) {
            metrics.count("step_up_failed", AccountDeletionErrorCode.VERIFICATION_FAILED.wireCode)
            verificationFailed()
        }
    }

    private fun verifyGoogle(user: UserEntity, idToken: String?) {
        val token = idToken?.takeIf(String::isNotBlank) ?: invalidRequest()
        val verified = googleVerifier.verify(token)
        if (!verified.emailVerified || user.email == null || verified.email != user.email) {
            metrics.count("step_up_failed", AccountDeletionErrorCode.VERIFICATION_FAILED.wireCode)
            verificationFailed()
        }
    }

    private fun requireUsableRequest(
        request: AccountDeletionRequestRecord?,
        userId: String,
    ): AccountDeletionRequestRecord {
        val value = request ?: invalidRequest()
        if (value.userId != userId) invalidRequest()
        if (!value.requestExpiresAt.isAfter(now())) {
            throw AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED)
        }
        if (value.status == AccountDeletionRequestStatus.PREFLIGHT_BLOCKED) {
            throw AccountDeletionException(AccountDeletionErrorCode.BLOCKED)
        }
        return value
    }

    private fun evaluateCommerce(userId: String): List<AccountDeletionBlocker> =
        when (val evaluation = commerceBlockerPort.evaluate(userId)) {
            AccountDeletionCommerceEvaluation.Eligible -> emptyList()
            is AccountDeletionCommerceEvaluation.Blocked -> evaluation.blockers
            AccountDeletionCommerceEvaluation.Unavailable -> {
                if (availability.commerceBypassAllowed()) emptyList()
                else throw AccountDeletionException(AccountDeletionErrorCode.BLOCKER_SERVICE_UNAVAILABLE)
            }
        }

    private fun chooseStepUp(user: UserEntity): Pair<AccountDeletionStepUpMethod, String?> = when {
        !user.email.isNullOrBlank() -> AccountDeletionStepUpMethod.GOOGLE to maskEmail(user.email)
        !user.phone.isNullOrBlank() -> AccountDeletionStepUpMethod.SMS to maskPhone(user.phone)
        else -> AccountDeletionStepUpMethod.NONE to null
    }

    private fun maskedCredential(user: UserEntity, method: AccountDeletionStepUpMethod): String? = when (method) {
        AccountDeletionStepUpMethod.SMS -> user.phone?.takeIf(String::isNotBlank)?.let(::maskPhone)
        AccountDeletionStepUpMethod.GOOGLE -> user.email?.takeIf(String::isNotBlank)?.let(::maskEmail)
        AccountDeletionStepUpMethod.NONE -> null
    }

    private fun AccountDeletionRequestRecord.isReusableAt(expectedUserId: String, now: LocalDateTime): Boolean =
        userId == expectedUserId &&
            policyVersion == ACCOUNT_DELETION_POLICY_VERSION &&
            status in setOf(AccountDeletionRequestStatus.PREFLIGHTED, AccountDeletionRequestStatus.CODE_SENT) &&
            requestExpiresAt.isAfter(now) &&
            terminalOutcome == null &&
            completedAt == null

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private fun requireActive(userId: String): UserEntity = try {
        lifecycleGuard.requireActiveForWrite(userId)
    } catch (_: IllegalStateException) {
        throw AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED)
    } catch (_: IllegalArgumentException) {
        throw AccountDeletionException(AccountDeletionErrorCode.REQUEST_INVALID)
    }

    private fun maskPhone(phone: String): String = when {
        phone.length <= 4 -> "****"
        else -> phone.take(3) + "****" + phone.takeLast(2)
    }

    private fun maskEmail(email: String): String {
        val separator = email.indexOf('@')
        if (separator <= 0) return "***"
        return email.take(1) + "***" + email.substring(separator)
    }

    private fun invalidRequest(): Nothing =
        throw AccountDeletionException(AccountDeletionErrorCode.REQUEST_INVALID)

    private fun verificationFailed(): Nothing =
        throw AccountDeletionException(AccountDeletionErrorCode.VERIFICATION_FAILED)

    private companion object {
        const val MAX_VERIFICATION_ATTEMPTS = 5
        val SMS_CODE = Regex("^[0-9]{6}$")
    }
}
