package com.joysong.app.domain.model

data class Translation(
    val translatedText: String,
    val detectedLanguage: String,
    val targetLanguage: String,
    val cached: Boolean
)
