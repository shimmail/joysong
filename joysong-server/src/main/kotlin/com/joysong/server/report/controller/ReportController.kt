package com.joysong.server.report.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.report.entity.dto.ReportRequest
import com.joysong.server.report.entity.dto.ReportResponse
import com.joysong.server.report.service.ReportService
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/reports")
class ReportController(private val reportService: ReportService) {

    /**
     * 提交举报（同一用户对同一目标只允许一条）
     */
    @PostMapping
    fun submitReport(
        authentication: Authentication,
        @Valid @RequestBody request: ReportRequest
    ): BaseResponse<ReportResponse> {
        val userId = authentication.principal as String
        val result = reportService.submitReport(userId, request)
        if (result is Map<*, *> && result.containsKey("error")) {
            @Suppress("UNCHECKED_CAST")
            val error = result["error"] as String
            @Suppress("UNCHECKED_CAST")
            val code = result["code"] as Int
            return BaseResponse.error<ReportResponse>(error, code)
        }
        return BaseResponse.success(result as ReportResponse)
    }

    /**
     * 检查当前用户是否已举报该目标
     */
    @GetMapping("/check/{targetType}/{targetId}")
    fun checkReported(
        authentication: Authentication,
        @PathVariable targetType: String,
        @PathVariable targetId: String
    ): BaseResponse<Map<String, Boolean>> {
        val userId = authentication.principal as String
        val reported = reportService.checkReported(userId, targetType, targetId)
        return BaseResponse.success(mapOf("reported" to reported))
    }
}
