package com.joysong.server.common.controller

import com.joysong.server.common.service.FileUploadService
import com.joysong.server.common.service.PublicUploadData
import com.joysong.server.common.service.PublicUploadMetrics
import com.joysong.server.common.service.PublicUploadReceipt
import com.joysong.server.common.service.PublicUploadResult
import com.joysong.server.common.service.PublicUploadResultType
import com.joysong.server.common.service.PublicUploadService
import com.joysong.server.common.service.PublicUploadStatus
import com.joysong.server.common.service.PublicUploadTimings
import com.joysong.server.user.deletion.UserMediaStorageProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.util.UUID

class FileUploadControllerTest {
    private val fileUploadService = mockk<FileUploadService>()
    private val publicUploadService = mockk<PublicUploadService>()
    private val metrics = mockk<PublicUploadMetrics>(relaxed = true)
    private val mvc = MockMvcBuilders
        .standaloneSetup(FileUploadController(fileUploadService, publicUploadService, metrics))
        .addFilters<StandaloneMockMvcBuilder>(PublicUploadRequestFilter())
        .build()

    @Test
    fun `idempotent upload returns complete payload and timing headers`() {
        val uploadId = UUID.randomUUID().toString()
        every {
            publicUploadService.upload("user-1", uploadId, any(), "diary", null, "request-1", any())
        } returns result(PublicUploadResultType.COMPLETE, PublicUploadStatus.COMPLETE, uploadId, "https://cdn/image.png")

        mvc.perform(
            multipart("/api/upload")
                .file(png())
                .param("folder", "diary")
                .header("Idempotency-Key", uploadId)
                .header("X-Request-ID", "request-1")
                .principal(user()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Request-ID", "request-1"))
            .andExpect(header().string("Server-Timing", org.hamcrest.Matchers.containsString("storage;dur=")))
            .andExpect(jsonPath("$.data.uploadId").value(uploadId))
            .andExpect(jsonPath("$.data.status").value("COMPLETE"))
            .andExpect(jsonPath("$.data.url").value("https://cdn/image.png"))
    }

    @Test
    fun `active upload returns 202 while a reused id returns 409`() {
        val pendingId = UUID.randomUUID().toString()
        val conflictId = UUID.randomUUID().toString()
        every {
            publicUploadService.upload("user-1", pendingId, any(), "diary", null, any(), any())
        } returns PublicUploadResult(
            PublicUploadResultType.PENDING,
            PublicUploadData(pendingId, PublicUploadStatus.PENDING, retryAfterMs = 1_000),
        )
        every {
            publicUploadService.upload("user-1", conflictId, any(), "diary", null, any(), any())
        } returns PublicUploadResult(
            PublicUploadResultType.CONFLICT,
            PublicUploadData(
                conflictId,
                PublicUploadStatus.FAILED,
                errorCode = "UPLOAD_ID_REUSED",
                retryable = false,
            ),
        )

        mvc.perform(
            multipart("/api/upload").file(png()).param("folder", "diary")
                .header("Idempotency-Key", pendingId).principal(user()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.errorCode").value("UPLOAD_IN_PROGRESS"))
            .andExpect(jsonPath("$.data.retryAfterMs").value(1_000))

        mvc.perform(
            multipart("/api/upload").file(png()).param("folder", "diary")
                .header("Idempotency-Key", conflictId).principal(user()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("UPLOAD_ID_REUSED"))
    }

    @Test
    fun `status lookup is owner scoped and hides missing records`() {
        val uploadId = UUID.randomUUID().toString()
        every { publicUploadService.status("user-1", uploadId) } returns null

        mvc.perform(get("/api/upload/status/{uploadId}", uploadId).principal(user()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("UPLOAD_NOT_FOUND"))
    }

    @Test
    fun `legacy upload keeps url-only success contract`() {
        every { fileUploadService.uploadDetailed("user-1", any(), "general", null) } returns PublicUploadReceipt(
            url = "http://localhost/images/general/image.png",
            storageKey = "general/image.png",
            provider = UserMediaStorageProvider.LOCAL_PUBLIC,
            contentSha256 = "a".repeat(64),
            contentLength = 12,
            contentType = MediaType.IMAGE_PNG_VALUE,
            stageDurationNanos = 1,
            storageDurationNanos = 1,
            registryDurationNanos = 1,
        )

        mvc.perform(multipart("/api/upload").file(png()).principal(user()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.url").value("http://localhost/images/general/image.png"))
            .andExpect(jsonPath("$.data.uploadId").doesNotExist())
    }

    private fun result(
        type: PublicUploadResultType,
        status: PublicUploadStatus,
        uploadId: String,
        url: String,
    ) = PublicUploadResult(
        type,
        PublicUploadData(uploadId, status, url = url, replayed = false),
        PublicUploadTimings(1, 2, 3),
    )

    private fun user() = UsernamePasswordAuthenticationToken("user-1", null, emptyList())

    private fun png() = MockMultipartFile(
        "file",
        "photo.png",
        MediaType.IMAGE_PNG_VALUE,
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        ),
    )
}
