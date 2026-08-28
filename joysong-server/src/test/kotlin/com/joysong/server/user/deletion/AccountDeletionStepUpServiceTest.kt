package com.joysong.server.user.deletion

import com.joysong.server.auth.service.AliyunSmsService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.mock.env.MockEnvironment
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class AccountDeletionStepUpServiceTest {
    private val availability = mockk<AccountDeletionAvailability> {
        every { requireEnabled() } just runs
        every { commerceBypassAllowed() } returns true
        every { developmentFixedSmsCode() } returns null
    }
    private val properties = AccountDeletionProperties().apply {
        hmacSecret = "0123456789abcdef-test"
    }
    private val guard = mockk<AccountLifecycleGuard>()
    private val store = mockk<AccountDeletionRequestStore>(relaxed = true)
    private val localBlockers = mockk<LocalAccountDeletionBlockerService>()
    private val commerce = mockk<AccountDeletionBlockerPort>()
    private val sms = mockk<AliyunSmsService>()
    private val google = mockk<GoogleAccountDeletionVerifier>()
    private val crypto = AccountDeletionCrypto(properties)
    private val clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC)
    private val metrics = mockk<AccountDeletionMetrics>(relaxed = true)
    private val service = AccountDeletionStepUpService(
        availability,
        properties,
        guard,
        store,
        localBlockers,
        commerce,
        sms,
        google,
        crypto,
        clock,
        metrics,
    )
    private val user = UserEntity(
        id = "user-1",
        phone = "+8613800138000",
        passwordHash = "hash",
    )

    @Test
    fun `preflight returns local blockers as a normal ineligible response`() {
        every { guard.requireActiveForWrite(user.id) } returns user
        every { localBlockers.evaluate(user) } returns listOf(
            AccountDeletionBlocker("IDENTITY_APPLICATION", 2, "VIEW_IDENTITY_APPLICATION"),
        )
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible
        val stored = slot<AccountDeletionRequestRecord>()
        every { store.insert(capture(stored)) } just runs

        val response = service.preflight(user.id)

        assertFalse(response.eligible)
        assertEquals("IDENTITY_APPLICATION", response.blockers.single().type)
        assertEquals(AccountDeletionRequestStatus.PREFLIGHT_BLOCKED, stored.captured.status)
        assertEquals(AccountDeletionStepUpMethod.SMS, response.stepUpMethod)
    }

    @Test
    fun `repeated eligible preflight reuses the active request and refreshes masked credential`() {
        val reusableStore = InMemoryRequestStore()
        val updatedUser = user.copy(phone = "+8613900139099")
        every { guard.requireActiveForWrite(user.id) } returnsMany listOf(user, updatedUser)
        every { localBlockers.evaluate(any()) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible
        val reusableService = service(requestStore = reusableStore)

        val first = reusableService.preflight(user.id)
        val second = reusableService.preflight(user.id)

        assertEquals(first.requestId, second.requestId)
        assertEquals("+86****99", second.maskedCredential)
        assertEquals(1, reusableStore.records.size)
    }

    @Test
    fun `preflight starts a new request when the step up method changes`() {
        val reusableStore = InMemoryRequestStore()
        val googleUser = user.copy(email = "owner@example.com")
        every { guard.requireActiveForWrite(user.id) } returnsMany listOf(user, googleUser)
        every { localBlockers.evaluate(any()) } returns emptyList()
        every { commerce.evaluate(user.id) } returns AccountDeletionCommerceEvaluation.Eligible
        val reusableService = service(requestStore = reusableStore)

        val smsPreflight = reusableService.preflight(user.id)
        val googlePreflight = reusableService.preflight(user.id)

        assertNotEquals(smsPreflight.requestId, googlePreflight.requestId)
        assertEquals(AccountDeletionStepUpMethod.GOOGLE, googlePreflight.stepUpMethod)
        assertEquals("o***@example.com", googlePreflight.maskedCredential)
        assertEquals(2, reusableStore.records.size)
    }

    @Test
    fun `development fixed sms code succeeds without Aliyun and is stored only as a digest`() {
        val environment = MockEnvironment()
            .withProperty("app.account-deletion.enabled", "true")
            .withProperty("app.account-deletion.dev-fixed-sms-code", "246810")
            .apply { setActiveProfiles("dev") }
        val developmentProperties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        Binder.get(environment).bind(
            "app.account-deletion",
            Bindable.ofInstance(developmentProperties),
        )
        val developmentCrypto = AccountDeletionCrypto(developmentProperties)
        val developmentStore = mockk<AccountDeletionRequestStore>(relaxed = true)
        every { guard.requireActiveForWrite(user.id) } returns user
        every { developmentStore.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.PREFLIGHTED,
        )
        every { sms.sendVerificationCode(any(), any()) } returns false
        val digest = slot<String>()
        every { developmentStore.storeSmsChallenge("request-1", capture(digest), any(), any()) } just runs
        val developmentService = AccountDeletionStepUpService(
            AccountDeletionAvailability(developmentProperties, environment),
            developmentProperties,
            guard,
            developmentStore,
            localBlockers,
            commerce,
            sms,
            google,
            developmentCrypto,
            clock,
            metrics,
        )

        val response = developmentService.sendSmsCode(user.id, "request-1")

        assertEquals("request-1", response.requestId)
        assertEquals(developmentCrypto.hash("${user.phone}:246810"), digest.captured)
        verify(exactly = 0) { sms.sendVerificationCode(any(), any()) }
    }

    @Test
    fun `sms challenge stores only a digest and enforces configured lifetime`() {
        every { guard.requireActiveForWrite(user.id) } returns user
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.PREFLIGHTED,
        )
        val rawCode = slot<String>()
        every { sms.sendVerificationCode(user.phone!!, capture(rawCode)) } returns true
        val digest = slot<String>()
        every { store.storeSmsChallenge("request-1", capture(digest), any(), any()) } just runs

        val response = service.sendSmsCode(user.id, "request-1")

        assertEquals(300, response.expiresInSeconds)
        assertEquals(60, response.resendAfterSeconds)
        assertEquals(64, digest.captured.length)
        assertFalse(digest.captured.matches(Regex("^[0-9]{6}$")))
        assertEquals(crypto.hash("${user.phone}:${rawCode.captured}"), digest.captured)
        verifyOrder {
            store.findForUpdate("request-1")
            guard.requireActiveForWrite(user.id)
        }
    }

    @Test
    fun `fifth invalid sms attempt is persisted and rejected`() {
        every { guard.requireActiveForWrite(user.id) } returns user
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.CODE_SENT,
            attemptCount = 4,
            codeHash = crypto.hash("123456"),
            verificationExpiresAt = LocalDateTime.now(clock).plusMinutes(1),
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            service.stepUp(user.id, AccountDeletionStepUpRequest("request-1", code = "000000"))
        }

        assertEquals(AccountDeletionErrorCode.VERIFICATION_FAILED, error.errorCode)
        verify(exactly = 1) { store.storeVerificationAttempt("request-1", 5) }
        verify(exactly = 0) { store.storeAuthorization(any(), any(), any()) }
    }

    @Test
    fun `sms verification binds the challenge to the current phone`() {
        every { guard.requireActiveForWrite(user.id) } returns user
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.CODE_SENT,
            codeHash = crypto.hash("${user.phone}:123456"),
            verificationExpiresAt = LocalDateTime.now(clock).plusMinutes(1),
        )

        val response = service.stepUp(
            user.id,
            AccountDeletionStepUpRequest("request-1", code = "123456"),
        )

        assertEquals("request-1", response.requestId)
    }

    @Test
    fun `sms verification rejects a code issued to the previously bound phone`() {
        val changedUser = user.copy(phone = "+8613900139099")
        every { guard.requireActiveForWrite(user.id) } returns changedUser
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.CODE_SENT,
            codeHash = crypto.hash("${user.phone}:123456"),
            verificationExpiresAt = LocalDateTime.now(clock).plusMinutes(1),
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            service.stepUp(user.id, AccountDeletionStepUpRequest("request-1", code = "123456"))
        }

        assertEquals(AccountDeletionErrorCode.VERIFICATION_FAILED, error.errorCode)
        verify(exactly = 0) { store.storeAuthorization(any(), any(), any()) }
    }

    @Test
    fun `sixth sms attempt is rejected without another counter update or comparison`() {
        every { guard.requireActiveForWrite(user.id) } returns user
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.SMS,
            status = AccountDeletionRequestStatus.CODE_SENT,
            attemptCount = 5,
            codeHash = crypto.hash("123456"),
            verificationExpiresAt = LocalDateTime.now(clock).plusMinutes(1),
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            service.stepUp(user.id, AccountDeletionStepUpRequest("request-1", code = "123456"))
        }

        assertEquals(AccountDeletionErrorCode.VERIFICATION_FAILED, error.errorCode)
        verify(exactly = 0) { store.storeVerificationAttempt(any(), any()) }
        verify(exactly = 0) { store.storeAuthorization(any(), any(), any()) }
    }

    @Test
    fun `google reauthentication requires the exact current email`() {
        val googleUser = user.copy(email = "Owner@example.com")
        every { guard.requireActiveForWrite(user.id) } returns googleUser
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.GOOGLE,
            status = AccountDeletionRequestStatus.PREFLIGHTED,
        )
        every { google.verify("token") } returns VerifiedGoogleIdentity(
            issuer = "https://accounts.google.com",
            audience = "client",
            email = "owner@example.com",
            emailVerified = true,
        )

        val error = assertThrows(AccountDeletionException::class.java) {
            service.stepUp(user.id, AccountDeletionStepUpRequest("request-1", googleIdToken = "token"))
        }

        assertEquals(AccountDeletionErrorCode.VERIFICATION_FAILED, error.errorCode)
    }

    @Test
    fun `successful google reauthentication returns raw authorization but stores only its digest`() {
        val googleUser = user.copy(email = "owner@example.com")
        every { guard.requireActiveForWrite(user.id) } returns googleUser
        every { store.findForUpdate("request-1") } returns request(
            method = AccountDeletionStepUpMethod.GOOGLE,
            status = AccountDeletionRequestStatus.PREFLIGHTED,
        )
        every { google.verify("token") } returns VerifiedGoogleIdentity(
            issuer = "https://accounts.google.com",
            audience = "client",
            email = googleUser.email!!,
            emailVerified = true,
        )
        val authorizationHash = slot<String>()
        every { store.storeAuthorization("request-1", capture(authorizationHash), any()) } just runs

        val response = service.stepUp(
            user.id,
            AccountDeletionStepUpRequest("request-1", googleIdToken = "token"),
        )

        assertTrue(response.deletionAuthorization.length >= 64)
        assertEquals(crypto.hash(response.deletionAuthorization), authorizationHash.captured)
        assertFalse(authorizationHash.captured.contains(response.deletionAuthorization))
    }

    private fun request(
        method: AccountDeletionStepUpMethod,
        status: AccountDeletionRequestStatus,
        attemptCount: Int = 0,
        codeHash: String? = null,
        verificationExpiresAt: LocalDateTime? = null,
    ) = AccountDeletionRequestRecord(
        id = "request-1",
        userId = user.id,
        policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
        status = status,
        stepUpMethod = method,
        verificationCodeHash = codeHash,
        verificationAttemptCount = attemptCount,
        verificationMaxAttempts = 5,
        verificationExpiresAt = verificationExpiresAt,
        resendAvailableAt = null,
        authorizationHash = null,
        authorizationExpiresAt = null,
        authorizationConsumedAt = null,
        idempotencyKeyHash = null,
        requestExpiresAt = LocalDateTime.now(clock).plusMinutes(30),
        terminalOutcome = null,
        terminalResultJson = null,
        completedAt = null,
    )

    private fun service(requestStore: AccountDeletionRequestStore) = AccountDeletionStepUpService(
        availability,
        properties,
        guard,
        requestStore,
        localBlockers,
        commerce,
        sms,
        google,
        crypto,
        clock,
        metrics,
    )

    private class InMemoryRequestStore : AccountDeletionRequestStore {
        val records = mutableListOf<AccountDeletionRequestRecord>()

        override fun insert(record: AccountDeletionRequestRecord) {
            records += record
        }

        override fun findForUpdate(requestId: String): AccountDeletionRequestRecord? =
            records.firstOrNull { it.id == requestId }

        override fun findReusableForUserForUpdate(
            userId: String,
            stepUpMethod: AccountDeletionStepUpMethod,
            now: LocalDateTime,
        ): AccountDeletionRequestRecord? = records
            .asReversed()
            .firstOrNull {
                it.userId == userId &&
                    it.policyVersion == ACCOUNT_DELETION_POLICY_VERSION &&
                    it.status in setOf(AccountDeletionRequestStatus.PREFLIGHTED, AccountDeletionRequestStatus.CODE_SENT) &&
                    it.stepUpMethod == stepUpMethod &&
                    it.requestExpiresAt.isAfter(now)
            }

        override fun storeSmsChallenge(
            requestId: String,
            codeHash: String,
            expiresAt: LocalDateTime,
            resendAvailableAt: LocalDateTime,
        ) = Unit

        override fun storeVerificationAttempt(requestId: String, attemptCount: Int) = Unit

        override fun storeAuthorization(requestId: String, authorizationHash: String, expiresAt: LocalDateTime) = Unit

        override fun consumeAuthorization(requestId: String, idempotencyKeyHash: String, consumedAt: LocalDateTime) = Unit

        override fun complete(
            requestId: String,
            status: AccountDeletionRequestStatus,
            outcome: AccountDeletionTerminalOutcome,
            terminalResultJson: String,
            completedAt: LocalDateTime,
        ) = Unit
    }
}
