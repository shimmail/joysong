package com.joysong.server.common.service

import com.aliyun.oss.OSS
import com.joysong.server.user.deletion.UserMediaAssetService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class PublicUploadServiceTest {
    @TempDir
    lateinit var uploadRoot: Path

    private val clock = Clock.fixed(Instant.parse("2026-09-03T03:00:00Z"), ZoneOffset.UTC)
    private val registry = mockk<UserMediaAssetService>(relaxed = true)
    private val attempts = FakeAttemptStore()
    private val metricsRegistry = SimpleMeterRegistry()
    private val metrics = PublicUploadMetrics(metricsRegistry)

    @Test
    fun `completed retry returns original URL without uploading twice`() {
        val service = service()
        val uploadId = UUID.randomUUID().toString()

        val first = service.upload("user-1", uploadId, png(), "diary", null, "request-1")
        val replay = service.upload("user-1", uploadId, png(), "diary", null, "request-2")

        assertEquals(PublicUploadResultType.COMPLETE, first.type)
        assertEquals(false, first.data.replayed)
        assertEquals(PublicUploadResultType.COMPLETE, replay.type)
        assertEquals(true, replay.data.replayed)
        assertEquals(first.data.url, replay.data.url)
        verify(exactly = 1) { registry.register("user-1", any(), "DIARY", any()) }
        assertFalse(Files.list(uploadRoot.resolve("staging")).use { it.findAny().isPresent })
    }

    @Test
    fun `same upload id with changed content is rejected without a second write`() {
        val service = service()
        val uploadId = UUID.randomUUID().toString()
        service.upload("user-1", uploadId, png(), "diary", null, "request-1")

        val conflict = service.upload(
            "user-1",
            uploadId,
            png(PNG_BYTES + byteArrayOf(0x01)),
            "diary",
            null,
            "request-2",
        )

        assertEquals(PublicUploadResultType.CONFLICT, conflict.type)
        assertEquals("UPLOAD_ID_REUSED", conflict.data.errorCode)
        verify(exactly = 1) { registry.register(any(), any(), any(), any()) }
    }

    @Test
    fun `active lease returns pending and does not reach media registry`() {
        attempts.forcePending = true
        val result = service().upload(
            "user-1",
            UUID.randomUUID().toString(),
            png(),
            "diary",
            null,
            "request-1",
        )

        assertEquals(PublicUploadResultType.PENDING, result.type)
        assertEquals(1_500, result.data.retryAfterMs)
        verify(exactly = 0) { registry.register(any(), any(), any(), any()) }
    }

    @Test
    fun `owner scoped object names prevent cross-user overwrite`() {
        val uploadId = UUID.randomUUID().toString()
        val service = service()

        service.upload("user-1", uploadId, png(), "diary", null, "request-1")
        service.upload("user-2", uploadId, png(), "diary", null, "request-2")

        val firstKey = attempts.findOwned("user-1", uploadId)!!.fingerprint.storageKey
        val secondKey = attempts.findOwned("user-2", uploadId)!!.fingerprint.storageKey
        assertNotEquals(firstKey, secondKey)
    }

    @Test
    fun `failed upload exposes stable owner-only status`() {
        every { registry.register(any(), any(), any(), any()) } throws IllegalStateException("database down")
        val uploadId = UUID.randomUUID().toString()
        val service = service()

        val failed = service.upload("user-1", uploadId, png(), "diary", null, "request-1")

        assertEquals(PublicUploadResultType.FAILED, failed.type)
        assertEquals("UPLOAD_FAILED", service.status("user-1", uploadId)?.errorCode)
        assertNull(service.status("user-2", uploadId))
    }

    @Test
    fun `idempotent upload rejects custom file name`() {
        assertThrows(IllegalArgumentException::class.java) {
            service().upload(
                "user-1",
                UUID.randomUUID().toString(),
                png(),
                "avatars",
                "user-1",
                "request-1",
            )
        }
    }

    @Test
    fun `upload id must use canonical lowercase UUID`() {
        assertThrows(IllegalArgumentException::class.java) {
            service().upload(
                "user-1",
                UUID.randomUUID().toString().uppercase(),
                png(),
                "diary",
                null,
                "request-1",
            )
        }
    }

    @Test
    fun `cleanup delegates seven-day retention cutoff processing`() {
        service().cleanExpiredAttempts()

        assertEquals(LocalDateTime.now(clock), attempts.lastDeleteExpiredAt)
    }

    private fun service() = PublicUploadService(
        fileUploadService = FileUploadService(
            baseUrl = "http://localhost:8080",
            uploadDirectory = uploadRoot.toString(),
            ossEnabled = false,
            ossEndpoint = "",
            ossBucketName = "",
            ossPublicBaseUrl = "",
            ossClientProvider = mockk<ObjectProvider<OSS>> {
                every { ifAvailable } returns null
            },
            userMediaAssetService = registry,
            uploadStagingDirectory = uploadRoot.resolve("staging").toString(),
            metrics = metrics,
        ),
        attemptStore = attempts,
        metrics = metrics,
        clock = clock,
    )

    private fun png(bytes: ByteArray = PNG_BYTES) = MockMultipartFile(
        "file",
        "photo.png",
        "image/png",
        bytes,
    )

    private class FakeAttemptStore : PublicUploadAttemptStore {
        private val records = mutableMapOf<Pair<String, String>, PublicUploadAttempt>()
        var forcePending = false
        var lastDeleteExpiredAt: LocalDateTime? = null

        override fun claim(
            ownerUserId: String,
            clientUploadId: String,
            fingerprint: PublicUploadFingerprint,
            now: LocalDateTime,
        ): PublicUploadClaim {
            if (forcePending) return PublicUploadClaim.Pending(1_500)
            val key = ownerUserId to clientUploadId
            val current = records[key]
            if (current != null && current.fingerprint != fingerprint) return PublicUploadClaim.Conflict
            if (current?.status == PublicUploadStatus.COMPLETE) {
                return PublicUploadClaim.Complete(requireNotNull(current.publicUrl))
            }
            if (current?.status == PublicUploadStatus.PENDING && current.leaseExpiresAt?.isAfter(now) == true) {
                return PublicUploadClaim.Pending(1_500)
            }
            val token = UUID.randomUUID().toString()
            records[key] = PublicUploadAttempt(
                ownerUserId,
                clientUploadId,
                fingerprint,
                PublicUploadStatus.PENDING,
                token,
                now.plus(Duration.ofSeconds(180)),
                null,
                null,
                null,
            )
            return PublicUploadClaim.Acquired(token)
        }

        override fun complete(
            ownerUserId: String,
            clientUploadId: String,
            leaseToken: String,
            publicUrl: String,
            now: LocalDateTime,
        ) {
            val key = ownerUserId to clientUploadId
            val current = requireNotNull(records[key])
            check(current.leaseToken == leaseToken)
            records[key] = current.copy(
                status = PublicUploadStatus.COMPLETE,
                leaseToken = null,
                leaseExpiresAt = null,
                publicUrl = publicUrl,
            )
        }

        override fun fail(
            ownerUserId: String,
            clientUploadId: String,
            leaseToken: String,
            errorCode: String,
            retryable: Boolean,
            now: LocalDateTime,
        ) {
            val key = ownerUserId to clientUploadId
            val current = requireNotNull(records[key])
            if (current.leaseToken != leaseToken) return
            records[key] = current.copy(
                status = PublicUploadStatus.FAILED,
                leaseToken = null,
                leaseExpiresAt = null,
                failureCode = errorCode,
                failureRetryable = retryable,
            )
        }

        override fun findOwned(ownerUserId: String, clientUploadId: String): PublicUploadAttempt? =
            records[ownerUserId to clientUploadId]

        override fun deleteExpired(now: LocalDateTime): Int {
            lastDeleteExpiredAt = now
            return 0
        }
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}
