package com.joysong.app.data.remote.dto

data class TranslateTextRequestDto(
    val text: String,
    val targetLanguage: String,
    val contentType: String = "general"
)

data class TranslationResponseDto(
    val translatedText: String = "",
    val detectedLanguage: String = "und",
    val targetLanguage: String = "",
    val provider: String = "AI",
    val cached: Boolean = false
)
