package com.joysong.server.translation.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class TranslateTextRequest(
    @field:NotBlank(message = "待翻译内容不能为空")
    @field:Size(max = 12000, message = "单次翻译内容不能超过12000个字符")
    val text: String,
    @field:NotBlank(message = "目标语言不能为空")
    @field:Size(max = 35, message = "目标语言格式不正确")
    @field:Pattern(
        regexp = "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$",
        message = "目标语言需使用BCP 47格式"
    )
    val targetLanguage: String,
    @field:Pattern(
        regexp = "^(diary|comment|article|article_html|project|project_html|institution|doctor|message|general)$",
        message = "不支持的内容类型"
    )
    val contentType: String = "general"
)

data class TranslationResponse(
    val translatedText: String,
    val detectedLanguage: String,
    val targetLanguage: String,
    val provider: String = "AI",
    val cached: Boolean = false
)
