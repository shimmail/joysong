package com.joysong.server.common.service

import com.aliyun.oss.OSS
import com.aliyun.oss.model.PutObjectRequest
import com.joysong.server.user.deletion.UserMediaAssetService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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

    @Test
    fun `mislabeled image is stored using its detected format`() {
        val service = service(ossEnabled = false)
        val jpegNamedPng = MockMultipartFile(
            "file",
            "avatar.png",
            "image/png",
            byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(),
                0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
            )
        )

        val url = service.upload(jpegNamedPng, folder = "avatars", customFileName = "user-2")

        assertTrue(url == "http://localhost:8080/images/avatars/user-2.jpg")
        assertTrue(Files.exists(uploadRoot.resolve("avatars/user-2.jpg")))
    }

    @Test
    fun `OSS upload trims deployment coordinates before selecting its bucket`() {
        val oss = mockk<OSS>(relaxed = true)
        val provider = mockk<ObjectProvider<OSS>> {
            every { ifAvailable } returns oss
        }
        val service = service(
            ossEnabled = true,
            ossEndpoint = " https://oss-cn-hangzhou.aliyuncs.com ",
            ossBucketName = " joysong-test ",
            ossClientProvider = provider,
        )

        val url = service.upload(validPng(), folder = "avatars", customFileName = "user-1")

        val request = slot<PutObjectRequest>()
        verify(exactly = 1) { oss.putObject(capture(request)) }
        assertEquals("joysong-test", request.captured.bucketName)
        assertEquals("avatars/user-1.png", request.captured.key)
        assertEquals(PNG_BYTES.size.toLong(), request.captured.metadata.contentLength)
        assertFalse(request.captured.file.exists(), "staged file must be deleted after OSS returns")
        assertEquals(
            "https://joysong-test.oss-cn-hangzhou.aliyuncs.com/avatars/user-1.png",
            url,
        )
    }

    @Test
    fun `upload streams from input and never materializes MultipartFile bytes`() {
        val file = mockk<MultipartFile>()
        every { file.isEmpty } returns false
        every { file.size } returns PNG_BYTES.size.toLong()
        every { file.originalFilename } returns "avatar.png"
        every { file.inputStream } returns ByteArrayInputStream(PNG_BYTES)
        every { file.bytes } throws AssertionError("MultipartFile bytes must not be read")

        val url = service(ossEnabled = false).upload(file, "avatars", "user-3")

        assertEquals("http://localhost:8080/images/avatars/user-3.png", url)
        verify(exactly = 0) { file.bytes }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `staged upload has the exact digest and remains reopenable until closed`() {
        val prepared = service(ossEnabled = false).prepare(validPng())

        assertEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(PNG_BYTES)
                .joinToString("") { "%02x".format(it) },
            prepared.contentSha256,
        )
        assertArrayEquals(PNG_BYTES, Files.readAllBytes(prepared.temporaryPath))
        assertArrayEquals(PNG_BYTES, Files.readAllBytes(prepared.temporaryPath))

        prepared.close()
        assertFalse(Files.exists(prepared.temporaryPath))
    }

    @Test
    fun `streamed content over ten MiB is rejected and staging is cleaned`() {
        val file = mockk<MultipartFile>()
        val oversized = ByteArray(10 * 1024 * 1024 + 1).also {
            PNG_BYTES.copyInto(it)
        }
        every { file.isEmpty } returns false
        every { file.size } returns 1
        every { file.originalFilename } returns "oversized.png"
        every { file.inputStream } returns ByteArrayInputStream(oversized)

        assertThrows(IllegalArgumentException::class.java) {
            service(ossEnabled = false).prepare(file)
        }
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `OSS receives a file request that can be reopened`() {
        val oss = mockk<OSS>(relaxed = true)
        every { oss.putObject(any<PutObjectRequest>()) } answers {
            val staged = firstArg<PutObjectRequest>().file
            assertTrue(staged.exists())
            assertArrayEquals(PNG_BYTES, staged.readBytes())
            assertArrayEquals(PNG_BYTES, staged.readBytes())
            mockk(relaxed = true)
        }
        val provider = mockk<ObjectProvider<OSS>> {
            every { ifAvailable } returns oss
        }

        service(ossEnabled = true, ossClientProvider = provider)
            .upload(validPng(), folder = "avatars", customFileName = "user-4")

        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `registry failure compensates an OSS object and removes staging file`() {
        val oss = mockk<OSS>(relaxed = true)
        val provider = mockk<ObjectProvider<OSS>> {
            every { ifAvailable } returns oss
        }
        val registry = mockk<UserMediaAssetService>()
        every { registry.register(any(), any(), any(), any()) } throws IllegalStateException("registry unavailable")
        val service = service(
            ossEnabled = true,
            ossClientProvider = provider,
            userMediaAssetService = registry,
        )

        assertThrows(IllegalStateException::class.java) {
            service.upload("user-1", validPng(), "avatars", "user-1")
        }

        verify(exactly = 1) { oss.deleteObject("joysong-test", "avatars/user-1.png") }
        assertTrue(stagingFiles().isEmpty())
    }

    private fun service(
        ossEnabled: Boolean,
        ossEndpoint: String = "https://oss-cn-hangzhou.aliyuncs.com",
        ossBucketName: String = "joysong-test",
        ossClientProvider: ObjectProvider<OSS> = unavailableOss,
        userMediaAssetService: UserMediaAssetService? = null,
    ) = FileUploadService(
        baseUrl = "http://localhost:8080",
        uploadDirectory = uploadRoot.toString(),
        ossEnabled = ossEnabled,
        ossEndpoint = ossEndpoint,
        ossBucketName = ossBucketName,
        ossPublicBaseUrl = "",
        ossClientProvider = ossClientProvider,
        userMediaAssetService = userMediaAssetService,
        uploadStagingDirectory = uploadRoot.resolve("staging").toString(),
    )

    private fun validPng() = MockMultipartFile(
        "file",
        "avatar.png",
        "image/png",
        PNG_BYTES,
    )

    private fun stagingFiles(): List<Path> {
        val staging = uploadRoot.resolve("staging")
        if (!Files.exists(staging)) return emptyList()
        return Files.list(staging).use { it.toList() }
    }

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}
