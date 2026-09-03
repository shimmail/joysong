package com.joysong.server.common.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.common.service.FileUploadService
import com.joysong.server.common.service.PublicUploadData
import com.joysong.server.common.service.PublicUploadMetrics
import com.joysong.server.common.service.PublicUploadReceipt
import com.joysong.server.common.service.PublicUploadResult
import com.joysong.server.common.service.PublicUploadResultType
import com.joysong.server.common.service.PublicUploadService
import com.joysong.server.common.service.PublicUploadTimings
import com.joysong.server.common.service.UploadMetricOutcome
import com.joysong.server.common.service.UploadMetricPhase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.Authentication
import java.time.Duration
import java.util.Locale

@RestController
@RequestMapping("/api/upload")
class FileUploadController(
    private val fileUploadService: FileUploadService,
    private val publicUploadService: PublicUploadService,
    private val metrics: PublicUploadMetrics,
) {
    @PostMapping
    fun upload(
        authentication: Authentication,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("folder", defaultValue = "general") folder: String,
        @RequestParam("customFileName", required = false) customFileName: String?,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<BaseResponse<*>> {
        val userId = authentication.principal as String
        val isAdmin = authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        require(customFileName == null || isAdmin || customFileName == userId) {
            "自定义文件名只能使用当前用户 ID"
        }
        val requestStartedAt = request.startedAt()
        val controllerEnteredAt = System.nanoTime()
        metrics.recordPhase(
            UploadMetricPhase.MULTIPART,
            (controllerEnteredAt - requestStartedAt).coerceAtLeast(0),
        )
        if (idempotencyKey == null) {
            var outcome = UploadMetricOutcome.FAILED
            var contentLength: Long? = null
            try {
                val receipt = fileUploadService.uploadDetailed(userId, file, folder, customFileName)
                contentLength = receipt.contentLength
                outcome = UploadMetricOutcome.LEGACY_COMPLETE
                response.setServerTiming(requestStartedAt, controllerEnteredAt, receipt.toTimings())
                return ResponseEntity.ok(BaseResponse.success(mapOf("url" to receipt.url)))
            } finally {
                metrics.recordRequest(outcome, System.nanoTime() - requestStartedAt, contentLength)
            }
        }

        val result = try {
            publicUploadService.upload(
                ownerUserId = userId,
                uploadId = idempotencyKey,
                file = file,
                folder = folder,
                customFileName = customFileName,
                requestId = request.requestId(),
                requestStartedAtNanos = requestStartedAt,
            )
        } catch (error: IllegalArgumentException) {
            response.setServerTiming(requestStartedAt, controllerEnteredAt, PublicUploadTimings())
            val errorCode = error.message.takeIf {
                it == "INVALID_UPLOAD_ID" || it == "IDEMPOTENT_CUSTOM_FILE_NAME_UNSUPPORTED"
            } ?: "INVALID_UPLOAD"
            return ResponseEntity.badRequest().body(
                BaseResponse.error<Nothing>(error.message ?: "上传请求不正确", 400, errorCode),
            )
        } catch (_: Exception) {
            response.setServerTiming(requestStartedAt, controllerEnteredAt, PublicUploadTimings())
            val data = PublicUploadData(
                uploadId = idempotencyKey,
                status = com.joysong.server.common.service.PublicUploadStatus.FAILED,
                replayed = false,
                errorCode = "UPLOAD_FAILED",
                retryable = true,
            )
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                BaseResponse(
                    code = HttpStatus.SERVICE_UNAVAILABLE.value(),
                    message = "上传暂时失败",
                    errorCode = "UPLOAD_FAILED",
                    data = data,
                ),
            )
        }
        response.setServerTiming(requestStartedAt, controllerEnteredAt, result.timings)
        return result.toResponse()
    }

    @GetMapping("/status/{uploadId}")
    fun status(
        authentication: Authentication,
        @PathVariable uploadId: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<BaseResponse<*>> {
        val startedAt = request.startedAt()
        val data = try {
            publicUploadService.status(authentication.principal as String, uploadId)
        } catch (error: IllegalArgumentException) {
            response.setServerTiming(startedAt, System.nanoTime(), PublicUploadTimings())
            return ResponseEntity.badRequest().body(
                BaseResponse.error<Nothing>(error.message ?: "上传编号不正确", 400, "INVALID_UPLOAD_ID"),
            )
        } catch (_: Exception) {
            response.setServerTiming(startedAt, System.nanoTime(), PublicUploadTimings())
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                BaseResponse.error<Nothing>("上传状态暂时不可用", 503, "UPLOAD_STATUS_UNAVAILABLE"),
            )
        }
        response.setServerTiming(startedAt, System.nanoTime(), PublicUploadTimings())
        return data?.let { ResponseEntity.ok(BaseResponse.success(it)) }
            ?: ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BaseResponse.error<Nothing>("上传记录不存在", 404, "UPLOAD_NOT_FOUND"))
    }

    private fun PublicUploadResult.toResponse(): ResponseEntity<BaseResponse<*>> = when (type) {
        PublicUploadResultType.COMPLETE -> ResponseEntity.ok(BaseResponse.success(data))
        PublicUploadResultType.PENDING -> ResponseEntity.status(HttpStatus.ACCEPTED).body(
            BaseResponse(
                code = HttpStatus.ACCEPTED.value(),
                message = "UPLOAD_IN_PROGRESS",
                errorCode = "UPLOAD_IN_PROGRESS",
                data = data,
            ),
        )
        PublicUploadResultType.CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT).body(
            BaseResponse(
                code = HttpStatus.CONFLICT.value(),
                message = "UPLOAD_ID_REUSED",
                errorCode = "UPLOAD_ID_REUSED",
                data = data,
            ),
        )
        PublicUploadResultType.FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            BaseResponse(
                code = HttpStatus.SERVICE_UNAVAILABLE.value(),
                message = "上传暂时失败",
                errorCode = data.errorCode ?: "UPLOAD_FAILED",
                data = data,
            ),
        )
    }

    private fun HttpServletRequest.requestId(): String =
        getAttribute(PublicUploadRequestFilter.REQUEST_ID_ATTRIBUTE) as? String
            ?: getHeader(PublicUploadRequestFilter.REQUEST_ID_HEADER)
            ?: "unavailable"

    private fun HttpServletRequest.startedAt(): Long =
        getAttribute(PublicUploadRequestFilter.REQUEST_STARTED_ATTRIBUTE) as? Long ?: System.nanoTime()

    private fun HttpServletResponse.setServerTiming(
        requestStartedAt: Long,
        controllerEnteredAt: Long,
        timings: PublicUploadTimings,
    ) {
        val total = System.nanoTime() - requestStartedAt
        val parse = (controllerEnteredAt - requestStartedAt).coerceAtLeast(0)
        setHeader(
            "Server-Timing",
            listOf(
                "multipart;dur=${parse.millis()}",
                "stage;dur=${timings.stageDurationNanos.millis()}",
                "storage;dur=${timings.storageDurationNanos.millis()}",
                "registry;dur=${timings.registryDurationNanos.millis()}",
                "total;dur=${total.millis()}",
            ).joinToString(", "),
        )
    }

    private fun Long.millis(): String = String.format(
        Locale.ROOT,
        "%.1f",
        Duration.ofNanos(this).toNanos() / 1_000_000.0,
    )

    private fun PublicUploadReceipt.toTimings() = PublicUploadTimings(
        stageDurationNanos = stageDurationNanos,
        storageDurationNanos = storageDurationNanos,
        registryDurationNanos = registryDurationNanos,
    )
}
