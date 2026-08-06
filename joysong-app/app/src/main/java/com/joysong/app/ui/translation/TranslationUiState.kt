package com.joysong.app.ui.translation

sealed class TranslationUiState {
    data object Loading : TranslationUiState()
    data class Success(
        val translatedText: String,
        val showingTranslation: Boolean = true
    ) : TranslationUiState()
    data class Error(val message: String) : TranslationUiState()
}

fun TranslationUiState?.displayText(originalText: String): String =
    if (this is TranslationUiState.Success && showingTranslation) translatedText else originalText
