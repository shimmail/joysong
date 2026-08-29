package com.joysong.server.admin.controller

import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.service.RefundService
import com.joysong.server.refund.service.RefundEvidenceFileContent
import com.joysong.server.refund.service.RefundEvidenceFileService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class AdminRefundControllerTest {
    @TempDir
    lateinit var privateDirectory: Path

    @Test
    fun `admin refund evidence content returns no-store nosniff and safe inline disposition`() {
        val refundService = mockk<RefundService>()
        val evidenceService = mockk<RefundEvidenceFileService>()
        val file = privateDirectory.resolve("evidence.png")
        Files.write(file, PNG_BYTES)
        every { evidenceService.loadContentForAdmin("refund-1", "file-1") } returns
            RefundEvidenceFileContent(file, "退款 凭证.png", "image/png")

        val response = AdminRefundController(refundService, evidenceService)
            .evidenceContent("refund-1", "file-1")

        assertEquals(MediaType.IMAGE_PNG, response.headers.contentType)
        assertEquals(PNG_BYTES.size.toLong(), response.headers.contentLength)
        assertEquals("no-store", response.headers.cacheControl)
        assertEquals("nosniff", response.headers.getFirst("X-Content-Type-Options"))
        val disposition = ContentDisposition.parse(response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)!!)
        assertEquals("inline", disposition.type)
        assertEquals("退款 凭证.png", disposition.filename)
        assertEquals(file, response.body?.file?.toPath())
    }

    @Test
    fun `admin refund evidence content rejects a file not linked to the requested refund`() {
        val refundService = mockk<RefundService>()
        val evidenceService = mockk<RefundEvidenceFileService>()
        every { evidenceService.loadContentForAdmin("refund-1", "file-from-refund-2") } throws
            IllegalArgumentException("REFUND_EVIDENCE_NOT_FOUND")

        val error = assertThrows(IllegalArgumentException::class.java) {
            AdminRefundController(refundService, evidenceService)
                .evidenceContent("refund-1", "file-from-refund-2")
        }

        assertEquals("REFUND_EVIDENCE_NOT_FOUND", error.message)
    }

    @Test
    fun `retry endpoint forwards the authenticated admin to failed item retry`() {
        val refundService = mockk<RefundService>()
        val authentication = mockk<Authentication>()
        val refund = RefundEntity(
            id = "refund-1",
            orderId = "order-1",
            userId = "user-1",
            amount = BigDecimal("400.00"),
            reason = "retry",
            originalStatus = "SERVICE_ACTIVE",
            status = "PROCESSING"
        )
        every { authentication.principal } returns "admin-1"
        every { refundService.retryFailedProcessingRefund("refund-1", "admin-1") } returns refund

        val response = AdminRefundController(refundService, mockk()).retryRefund("refund-1", authentication)

        assertEquals(200, response.code)
        assertEquals(refund, response.data)
        verify(exactly = 1) { refundService.retryFailedProcessingRefund("refund-1", "admin-1") }
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}
