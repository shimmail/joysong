package com.joysong.server.comment.entity.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PublishCommentRequest(
    @field:NotBlank(message = "日记ID不能为空")
    val diaryId: String = "",
    @field:NotBlank(message = "评论内容不能为空")
    @field:Size(max = 1000, message = "评论内容不能超过1000字")
    val content: String = "",
    val parentId: String? = null,          // 父评论 ID，回复时必填
    val replyToUserId: String? = null      // 被回复用户 ID，回复时可选填
)
