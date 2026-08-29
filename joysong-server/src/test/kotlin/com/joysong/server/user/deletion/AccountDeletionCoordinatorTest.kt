package com.joysong.server.user.deletion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import io.micrometer.core.instrument.Timer
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.annotation.Transactional
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class AccountDeletionCoordinatorTest {
    private val properties = AccountDeletionProperties().apply {
        hmacSecret = "0123456789abcdef-test"
    }
    private val crypto = AccountDeletionCrypto(properties)
    private val clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC)
    private val availability = mockk<AccountDeletionAvailability> {
        every { requireEnabled() } just runs
        every { commerceBypassAllowed() } returns true
    }
    private val guard = mockk<AccountLifecycleGuard>()
    private val store = mockk<AccountDeletionRequestStore>(relaxed = true)
    private val localBlockers = mockk<LocalAccountDeletionBlockerService>()
    private val commerce = mockk<AccountDeletionBlockerPort>()
    private val eraser = mockk<AccountDeletionDataEraser>(relaxed = true)
    private val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val metrics = mockk<AccountDeletionMetrics>(relaxed = true).also {
        every { it.startTransaction() } returns mockk<Timer.Sample>(relaxed = true)
    }
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val coordinator = AccountDeletionCoordinator(
        availability,
        guard,
        store,
        localBlockers,
        commerce,
        eraser,
        crypto,
        mapper,
        publisher,
        clock,
        metrics,
    )
    private val user = UserEntity(
        id = "user-1",
        phone = "+8613800138000",
        email = "owner@example.com",
        passwordHash = "hash",
    )
    private val authorization = "a".repeat(64)
    private val idempotencyKey = "idempotency-key-0001"

    @Test
    fun `confirm performs final recheck and delegates all writes to the atomic eraser`() {
        val request = authorizedRequest()
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible

        val response = coordinator.confirm(user.id, idempotencyKey, authorization, command())

        assertEquals(AccountDeletionTerminalOutcome.ERASED, response.outcome)
        verifyOrder {
            store.findForUpdate(request.id)
            guard.requireActiveForWrite(user.id)
        }
        verify(exactly = 1) { eraser.erase(user, LocalDateTime.now(clock)) }
        verify(exactly = 1) {
            store.consumeAuthorization(request.id, crypto.hash(idempotencyKey), any())
        }
        verify(exactly = 1) {
            store.complete(request.id, AccountDeletionRequestStatus.COMPLETED, AccountDeletionTerminalOutcome.ERASED, any(), any())
        }
        verify(exactly = 1) { publisher.publishEvent(AccountErasedEvent(user.id, request.id)) }
    }

    @Test
    fun `final blocker becomes a repeatable terminal conflict without partial erasure`() {
        val request = authorizedRequest()
        val blocker = AccountDeletionBlocker("IDENTITY_APPLICATION", 1, "VIEW_IDENTITY_APPLICATION")
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns listOf(blocker)
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible

        val response = coordinator.confirm(user.id, idempotencyKey, authorization, command())

        assertEquals(AccountDeletionTerminalOutcome.BLOCKED, response.outcome)
        assertEquals(listOf(blocker), response.blockers)
        verify(exactly = 0) { eraser.erase(any(), any()) }
        verify(exactly = 1) {
            store.consumeAuthorization(request.id, crypto.hash(idempotencyKey), any())
        }
        verify(exactly = 1) {
            store.complete(request.id, AccountDeletionRequestStatus.BLOCKED, AccountDeletionTerminalOutcome.BLOCKED, any(), any())
        }
    }

    @Test
    fun `fixed administrator is blocked in the real deletion coordinator path`() {
        val fixedAdmin = user.copy(phone = "13800000000", role = "ADMIN")
        val request = authorizedRequest().copy(userId = fixedAdmin.id)
        val adminBlocker = AccountDeletionBlocker("ADMIN_ACCOUNT", 1, "CONTACT_SUPPORT")
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(fixedAdmin.id) } returns fixedAdmin
        every { localBlockers.evaluate(fixedAdmin) } returns listOf(adminBlocker)
        every { commerce.evaluate(fixedAdmin.id) } returns AccountDeletionCommerceEvaluation.Eligible

        val response = coordinator.confirm(fixedAdmin.id, idempotencyKey, authorization, command())

        assertEquals(AccountDeletionTerminalOutcome.BLOCKED, response.outcome)
        assertEquals(listOf(adminBlocker), response.blockers)
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `anonymous retry can only read an exactly matching terminal result`() {
        val terminalResponse = AccountDeletionConfirmResponse(
            requestId = "request-1",
            outcome = AccountDeletionTerminalOutcome.ERASED,
            completedAt = LocalDateTime.now(clock),
        )
        every { store.findForUpdate("request-1") } returns authorizedRequest().copy(
            status = AccountDeletionRequestStatus.COMPLETED,
            authorizationConsumedAt = LocalDateTime.now(clock),
            idempotencyKeyHash = crypto.hash(idempotencyKey),
            terminalOutcome = AccountDeletionTerminalOutcome.ERASED,
            terminalResultJson = mapper.writeValueAsString(terminalResponse),
            completedAt = LocalDateTime.now(clock),
        )

        val replay = coordinator.confirm(null, idempotencyKey, authorization, command())

        assertEquals(terminalResponse, replay)
        verify(exactly = 0) { guard.requireActiveForWrite(any()) }
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `anonymous nonterminal confirmation is rejected before writes`() {
        every { store.findForUpdate("request-1") } returns authorizedRequest()

        val error = assertThrows(AccountDeletionException::class.java) {
            coordinator.confirm(null, idempotencyKey, authorization, command())
        }

        assertEquals(AccountDeletionErrorCode.UNAUTHENTICATED, error.errorCode)
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `terminal replay rejects a different policy version`() {
        val terminalResponse = AccountDeletionConfirmResponse(
            requestId = "request-1",
            outcome = AccountDeletionTerminalOutcome.ERASED,
            completedAt = LocalDateTime.now(clock),
        )
        every { store.findForUpdate("request-1") } returns authorizedRequest().copy(
            status = AccountDeletionRequestStatus.COMPLETED,
            authorizationConsumedAt = LocalDateTime.now(clock),
            idempotencyKeyHash = crypto.hash(idempotencyKey),
            terminalOutcome = AccountDeletionTerminalOutcome.ERASED,
            terminalResultJson = mapper.writeValueAsString(terminalResponse),
            completedAt = LocalDateTime.now(clock),
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            coordinator.confirm(
                null,
                idempotencyKey,
                authorization,
                command().copy(policyVersion = "different-policy"),
            )
        }

        assertEquals(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT, error.errorCode)
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `database idempotency uniqueness violation maps to stable conflict`() {
        val request = authorizedRequest()
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible
        every { store.consumeAuthorization(any(), any(), any()) } throws
            DataIntegrityViolationException("duplicate idempotency hash")

        val error = assertThrows(AccountDeletionException::class.java) {
            coordinator.confirm(user.id, idempotencyKey, authorization, command())
        }

        assertEquals(AccountDeletionErrorCode.IDEMPOTENCY_CONFLICT, error.errorCode)
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `account state race maps to stable expired authorization`() {
        val request = authorizedRequest()
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } throws IllegalStateException("账号不可用")

        val error = assertThrows(AccountDeletionException::class.java) {
            coordinator.confirm(user.id, idempotencyKey, authorization, command())
        }

        assertEquals(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED, error.errorCode)
        verify(exactly = 0) { eraser.erase(any(), any()) }
    }

    @Test
    fun `coordinator is the single transactional write boundary`() {
        val method = AccountDeletionCoordinator::class.java.getMethod(
            "confirm",
            String::class.java,
            String::class.java,
            String::class.java,
            ConfirmAccountDeletionRequest::class.java,
        )

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }

    @Test
    fun `successful observability is emitted only after the deletion transaction commits`() {
        val request = authorizedRequest()
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible

        TransactionSynchronizationManager.initSynchronization()
        try {
            coordinator.confirm(user.id, idempotencyKey, authorization, command())

            verify(exactly = 0) { metrics.count("success", any()) }
            verify(exactly = 0) { metrics.stopTransaction(any(), "ERASED") }
            val callback = TransactionSynchronizationManager.getSynchronizations().single()
            callback.afterCommit()
            callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)

            verify(exactly = 1) { metrics.count("success", any()) }
            verify(exactly = 1) { metrics.stopTransaction(any(), "ERASED") }
            verify(exactly = 0) { metrics.count("rollback", any()) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `rollback observability is emitted from transaction completion rather than the failing method body`() {
        val request = authorizedRequest()
        every { store.findForUpdate(request.id) } returns request
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible
        every { eraser.erase(user, any()) } throws IllegalStateException("database write failed")

        TransactionSynchronizationManager.initSynchronization()
        try {
            assertThrows(IllegalStateException::class.java) {
                coordinator.confirm(user.id, idempotencyKey, authorization, command())
            }

            verify(exactly = 0) { metrics.count("rollback", any()) }
            verify(exactly = 0) { metrics.stopTransaction(any(), "ROLLBACK") }
            val callback = TransactionSynchronizationManager.getSynchronizations().single()
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)

            verify(exactly = 1) { metrics.count("rollback", "INTERNAL_ERROR") }
            verify(exactly = 1) { metrics.stopTransaction(any(), "ROLLBACK") }
            verify(exactly = 0) { metrics.count("success", any()) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun authorizedRequest() = AccountDeletionRequestRecord(
        id = "request-1",
        userId = user.id,
        policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
        status = AccountDeletionRequestStatus.AUTHORIZED,
        stepUpMethod = AccountDeletionStepUpMethod.GOOGLE,
        verificationCodeHash = null,
        verificationAttemptCount = 0,
        verificationMaxAttempts = 5,
        verificationExpiresAt = null,
        resendAvailableAt = null,
        authorizationHash = crypto.hash(authorization),
        authorizationExpiresAt = LocalDateTime.now(clock).plusMinutes(10),
        authorizationConsumedAt = null,
        idempotencyKeyHash = null,
        requestExpiresAt = LocalDateTime.now(clock).plusMinutes(30),
        terminalOutcome = null,
        terminalResultJson = null,
        completedAt = null,
    )

    private fun command() = ConfirmAccountDeletionRequest(
        requestId = "request-1",
        policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
        confirmation = "DELETE",
    )
}
