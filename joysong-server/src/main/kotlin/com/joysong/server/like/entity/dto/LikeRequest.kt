package com.joysong.server.like.entity.dto

import jakarta.validation.constraints.NotBlank

data class LikeRequest(
    @field:NotBlank(message = "目标类型不能为空")
    val targetType: String = "",
    @field:NotBlank(message = "目标ID不能为空")
    val targetId: String = ""
)
