package com.joysong.server.common.service

import com.aliyun.oss.OSS
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

class FileUploadServiceSafetyTest {

    @TempDir
    lateinit var uploadRoot: Path

    private val unavailableOss = mockk<ObjectProvider<OSS>>().also {
        every { it.ifAvailable } returns null
    }

    @Test
    fun `enabled OSS never falls back to local storage`() {
        val service = service(ossEnabled = true)

        val error = assertThrows(IllegalStateException::class.java) {
            service.upload(validPng(), folder = "avatars", customFileName = "user-1")
        }

        assertTrue(error.message == "STORAGE_PROVIDER_UNAVAILABLE")
        assertFalse(Files.exists(uploadRoot.resolve("avatars/user-1.png")))
    }

    @Test
    fun `disabled OSS keeps explicit local development upload available`() {
        val service = service(ossEnabled = false)

        val url = service.upload(validPng(), folder = "avatars", customFileName = "user-1")

        assertTrue(url == "http://localhost:8080/images/avatars/user-1.png")
        assertTrue(Files.exists(uploadRoot.resolve("avatars/user-1.png")))
    }

    private fun service(ossEnabled: Boolean) = FileUploadService(
        baseUrl = "http://localhost:8080",
        uploadDirectory = uploadRoot.toString(),
        ossEnabled = ossEnabled,
        ossEndpoint = "oss-cn-hangzhou.aliyuncs.com",
        ossBucketName = "joysong-test",
        ossPublicBaseUrl = "",
        ossClientProvider = unavailableOss
    )

    private fun validPng() = MockMultipartFile(
        "file",
        "avatar.png",
        "image/png",
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00
        )
    )
}
