package com.joysong.server.user.deletion

import com.aliyun.oss.OSS
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path

class UserMediaDeletionWorkerTest {
    @TempDir lateinit var root: Path

    private lateinit var worker: UserMediaDeletionWorker

    @BeforeEach
    fun setUp() {
        worker = UserMediaDeletionWorker(
            jdbcTemplate = mockk<JdbcTemplate>(relaxed = true),
            publicUploadDirectory = root.toString(),
            privateUploadDirectory = root.toString(),
            ossBucketName = "",
            ossClientProvider = mockk<ObjectProvider<OSS>>(relaxed = true),
            metrics = mockk<AccountDeletionMetrics>(relaxed = true),
        )
    }

    @Test
    fun `missing registered local object is already a successful deletion`() {
        assertDoesNotThrow { worker.deleteLocal(root.toString(), "avatars/missing.png") }
    }

    @Test
    fun `registered local object is deleted inside the configured root`() {
        val file = root.resolve("avatars/user.png")
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(1, 2, 3))

        worker.deleteLocal(root.toString(), "avatars/user.png")

        assertFalse(Files.exists(file))
    }

    @Test
    fun `storage traversal never reaches an object outside the configured root`() {
        assertThrows(IllegalArgumentException::class.java) {
            UserMediaAssetService.validateStorageKey("../outside.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker.deleteLocal(root.toString(), "../outside.png")
        }
    }
}
