package com.joysong.server.favorite.entity.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AddFavoriteRequest(
    @field:NotBlank(message = "目标类型不能为空")
    val targetType: String = "",
    @field:NotBlank(message = "目标ID不能为空")
    val targetId: String = "",
    @field:Size(max = 100, message = "名称不能超过100字符")
    val targetName: String = "",
    val targetImage: String = ""
)
