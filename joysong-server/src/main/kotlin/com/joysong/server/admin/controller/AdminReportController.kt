package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.report.service.ReportService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminReportController(
    private val reportService: ReportService
) {

    @GetMapping("/reports")
    fun listReports(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "false") deleted: Boolean
    ): BaseResponse<*> {
        return BaseResponse.success(reportService.adminListReports(status, deleted))
    }

    @PutMapping("/reports/{id}/status")
    fun updateReportStatus(
        @PathVariable id: String,
        @RequestBody body: Map<String, String>
    ): BaseResponse<*> {
        val newStatus = body["status"] ?: return BaseResponse.error<Any>("状态不能为空")
        val (success, message) = reportService.adminUpdateStatus(id, newStatus)
        return if (success) BaseResponse.success(null) else BaseResponse.error<Any>(message)
    }

    @DeleteMapping("/reports/{id}")
    fun deleteReport(@PathVariable id: String): BaseResponse<*> {
        val (success, message) = reportService.adminSoftDelete(id)
        return if (success) BaseResponse.success(null) else BaseResponse.error<Any>(message)
    }

    @PutMapping("/reports/{id}/restore")
    fun restoreReport(@PathVariable id: String): BaseResponse<*> {
        val (success, message) = reportService.adminRestore(id)
        return if (success) BaseResponse.success(null) else BaseResponse.error<Any>(message)
    }

    @DeleteMapping("/reports/target/{targetType}/{targetId}")
    fun deleteReportTarget(
        @PathVariable targetType: String,
        @PathVariable targetId: String
    ): BaseResponse<*> {
        reportService.adminDeleteTarget(targetType, targetId)
        return BaseResponse.success(null)
    }
}
