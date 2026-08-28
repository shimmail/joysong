package com.joysong.server.identity.service

import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path

class PrivateIdentityFileServiceTransactionTest {
    @TempDir
    lateinit var privateDirectory: Path

    @Test
    fun `transaction rollback removes a private file written before database rollback`() {
        val service = service()
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.upload("user-1", "ID_CARD_FRONT", validPng())
            val storedFile = singleStoredFile()

            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            assertEquals(1, callbacks.size)
            callbacks.single().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)

            assertFalse(Files.exists(storedFile))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `successful transaction commit keeps the registered private file`() {
        val service = service()
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.upload("user-1", "ID_CARD_FRONT", validPng())
            val storedFile = singleStoredFile()

            val callbacks = TransactionSynchronizationManager.getSynchronizations()
            assertEquals(1, callbacks.size)
            callbacks.single().afterCommit()
            callbacks.single().afterCompletion(TransactionSynchronization.STATUS_COMMITTED)

            assertTrue(Files.isRegularFile(storedFile))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun service(): PrivateIdentityFileService {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val lifecycleGuard = mockk<AccountLifecycleGuard> {
            every { requireActiveForWrite("user-1") } returns mockk(relaxed = true)
        }
        val mediaAssets = mockk<UserMediaAssetService> {
            every { register(any(), any(), any(), any()) } just runs
        }
        return PrivateIdentityFileService(
            jdbcTemplate = jdbcTemplate,
            lifecycleGuard = lifecycleGuard,
            userMediaAssetService = mediaAssets,
            privateUploadDirectory = privateDirectory.toString(),
        )
    }

    private fun singleStoredFile(): Path = Files.walk(privateDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().single()
    }

    private fun validPng() = MockMultipartFile(
        "file",
        "identity.png",
        "image/png",
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        ),
    )
}
