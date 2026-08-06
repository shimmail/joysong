package com.joysong.server.report.entity.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ReportRequest(
    @field:NotBlank(message = "举报类型不能为空")
    val targetType: String = "",
    @field:NotBlank(message = "举报目标ID不能为空")
    val targetId: String = "",
    @field:NotBlank(message = "举报原因不能为空")
    val reason: String = "",
    @field:Size(max = 500, message = "举报描述不能超过500字")
    val description: String? = null
)
