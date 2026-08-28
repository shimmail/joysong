package com.joysong.server.user.deletion

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.user.service.AccountLifecycleGuard
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.LocalDateTime

@Service
class AccountDeletionCoordinator(
    private val availability: AccountDeletionAvailability,
    private val lifecycleGuard: AccountLifecycleGuard,
    private val requestStore: AccountDeletionRequestStore,
    private val localBlockerService: LocalAccountDeletionBlockerService,
    private val commerceBlockerPort: AccountDeletionBlockerPort,
    private val dataEraser: AccountDeletionDataEraser,
    private val crypto: AccountDeletionCrypto,
    private val objectMapper: ObjectMapper,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
    private val metrics: AccountDeletionMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun confirm(
        authenticatedUserId: String?,
        idempotencyKey: String,
        deletionAuthorization: String,
        command: ConfirmAccountDeletionRequest,
    ): AccountDeletionConfirmResponse {
        availability.requireEnabled()
        validateCommand(idempotencyKey, deletionAuthorization, command)
        val sample = metrics.startTransaction()
        val transactionObservation = observeTransaction(sample)
        try {
            val request = requestStore.findForUpdate(command.requestId)
                ?: throw AccountDeletionException(AccountDeletionErrorCode.REQUEST_INVALID)
            recoverTerminal(request, authenticatedUserId, idempotencyKey, deletionAuthorization, command)?.let {
                transactionObservation.committedOutcome = "REPLAY"
                return it
            }
            val userId = authenticatedUserId
                ?: throw AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED)
            if (request.userId != userId) throw AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED)

            val user = try {
                lifecycleGuard.requireActiveForWrite(userId)
            } catch (_: IllegalStateException) {
                throw AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED)
            } catch (_: IllegalArgumentException) {
                throw AccountDeletionException(AccountDeletionErrorCode.REQUEST_INVALID)
            }
            requireAuthorization(request, userId, idempotencyKey, deletionAuthorization, command)

            val blockers = localBlockerService.evaluate(user).toMutableList()
            blockers += evaluateCommerce(userId)
            val now = now()
            val idempotencyHash = crypto.hash(idempotencyKey)
            requestStore.consumeAuthorization(request.id, idempotencyHash, now)
            if (blockers.isNotEmpty()) {
                val response = AccountDeletionConfirmResponse(
                    requestId = request.id,
                    outcome = AccountDeletionTerminalOutcome.BLOCKED,
                    completedAt = now,
                    blockers = blockers,
                )
                requestStore.complete(
                    request.id,
                    AccountDeletionRequestStatus.BLOCKED,
                    AccountDeletionTerminalOutcome.BLOCKED,
                    objectMapper.writeValueAsString(response),
                    now,
                )
                transactionObservation.committedOutcome = "BLOCKED"
                transactionObservation.afterCommit = {
                    logger.info("Account deletion final check blocked userId={} requestId={}", userId, request.id)
                    metrics.count("blocked", AccountDeletionErrorCode.BLOCKED.wireCode)
                }
                return response
            }

            dataEraser.erase(user, now)
            val response = AccountDeletionConfirmResponse(
                requestId = request.id,
                outcome = AccountDeletionTerminalOutcome.ERASED,
                completedAt = now,
            )
            requestStore.complete(
                request.id,
                AccountDeletionRequestStatus.COMPLETED,
                AccountDeletionTerminalOutcome.ERASED,
                objectMapper.writeValueAsString(response),
                now,
            )
            eventPublisher.publishEvent(AccountErasedEvent(userId, request.id))
            transactionObservation.committedOutcome = "ERASED"
            transactionObservation.afterCommit = {
                logger.info("Account deletion completed userId={} requestId={}", userId, request.id)
                metrics.count("success")
            }
            return response
        } catch (error: DataIntegrityViolationException) {
            transactionObservation.rollbackErrorCode = AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT.wireCode
            throw AccountDeletionException(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT)
        } catch (error: RuntimeException) {
            transactionObservation.rollbackErrorCode =
                (error as? AccountDeletionException)?.errorCode?.wireCode ?: "INTERNAL_ERROR"
            throw error
        }
    }

    private fun observeTransaction(sample: io.micrometer.core.instrument.Timer.Sample): TransactionObservation {
        val observation = TransactionObservation()
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return observation
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                safelyObserve(observation.afterCommit)
                safelyObserve { metrics.stopTransaction(sample, observation.committedOutcome) }
            }

            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return
                safelyObserve { metrics.count("rollback", observation.rollbackErrorCode) }
                safelyObserve { metrics.stopTransaction(sample, "ROLLBACK") }
            }
        })
        return observation
    }

    private fun safelyObserve(action: (() -> Unit)?) {
        if (action == null) return
        try {
            action()
        } catch (error: RuntimeException) {
            logger.warn("Account deletion observability callback failed type={}", error.javaClass.simpleName)
        }
    }

    private fun recoverTerminal(
        request: AccountDeletionRequestRecord,
        authenticatedUserId: String?,
        idempotencyKey: String,
        deletionAuthorization: String,
        command: ConfirmAccountDeletionRequest,
    ): AccountDeletionConfirmResponse? {
        if (request.status !in TERMINAL_STATUSES) return null
        if (authenticatedUserId != null && authenticatedUserId != request.userId) {
            throw AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED)
        }
        if (!crypto.matches(idempotencyKey, request.idempotencyKeyHash) ||
            !crypto.matches(deletionAuthorization, request.authorizationHash) ||
            command.policyVersion != request.policyVersion
        ) {
            throw AccountDeletionException(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT)
        }
        val json = request.terminalResultJson
            ?: throw AccountDeletionException(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT)
        return objectMapper.readValue(json, AccountDeletionConfirmResponse::class.java)
    }

    private fun requireAuthorization(
        request: AccountDeletionRequestRecord,
        userId: String,
        idempotencyKey: String,
        deletionAuthorization: String,
        command: ConfirmAccountDeletionRequest,
    ) {
        if (request.userId != userId) throw AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED)
        if (request.policyVersion != command.policyVersion) {
            throw AccountDeletionException(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT)
        }
        if (request.idempotencyKeyHash != null && !crypto.matches(idempotencyKey, request.idempotencyKeyHash)) {
            throw AccountDeletionException(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT)
        }
        val authorized = request.status == AccountDeletionRequestStatus.AUTHORIZED &&
            request.authorizationConsumedAt == null &&
            request.authorizationExpiresAt?.isAfter(now()) == true &&
            crypto.matches(deletionAuthorization, request.authorizationHash)
        if (!authorized) throw AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED)
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

    private fun validateCommand(
        idempotencyKey: String,
        deletionAuthorization: String,
        command: ConfirmAccountDeletionRequest,
    ) {
        if (idempotencyKey.length !in 16..256 || deletionAuthorization.length !in 32..512 ||
            command.requestId.isBlank() || command.policyVersion.isBlank() || command.confirmation != "DELETE"
        ) {
            throw AccountDeletionException(AccountDeletionErrorCode.REQUEST_INVALID)
        }
    }

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private companion object {
        val TERMINAL_STATUSES = setOf(AccountDeletionRequestStatus.COMPLETED, AccountDeletionRequestStatus.BLOCKED)
    }

    private class TransactionObservation(
        var committedOutcome: String = "COMMITTED",
        var rollbackErrorCode: String = "INTERNAL_ERROR",
        var afterCommit: (() -> Unit)? = null,
    )
}
