package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.refund.service.RefundService
import com.joysong.server.refund.service.RefundEvidenceFileService
import org.springframework.core.io.FileSystemResource
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminRefundController(
    private val refundService: RefundService,
    private val refundEvidenceFileService: RefundEvidenceFileService,
) {

    @GetMapping("/refunds")
    fun listRefunds(): BaseResponse<*> = BaseResponse.success(refundService.adminListAll())

    @GetMapping("/refunds/{refundId}/evidence/{fileId}/content")
    fun evidenceContent(
        @PathVariable refundId: String,
        @PathVariable fileId: String,
    ): ResponseEntity<FileSystemResource> {
        val file = refundEvidenceFileService.loadContentForAdmin(refundId, fileId)
        val resource = FileSystemResource(file.path)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.contentType))
            .contentLength(resource.contentLength())
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline()
                    .filename(file.originalName, Charsets.UTF_8)
                    .build()
                    .toString(),
            )
            .body(resource)
    }

    @PutMapping("/refunds/{id}/status")
    fun updateRefundStatus(
        @PathVariable id: String,
        @RequestBody body: Map<String, String>,
        authentication: Authentication
    ): BaseResponse<*> {
        val newStatus = body["status"] ?: return BaseResponse.error<Any>("状态不能为空")
        val adminId = authentication.principal as String
        val rejectReason = body.getOrDefault("rejectReason", "")
        val result = refundService.adminUpdateStatus(id, newStatus, adminId, rejectReason)
            ?: return BaseResponse.error<Any>("退款记录不存在")
        return BaseResponse.success(result)
    }

    @PostMapping("/refunds/{id}/retry")
    fun retryRefund(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val adminId = authentication.principal as String
        return BaseResponse.success(refundService.retryFailedProcessingRefund(id, adminId))
    }
}
