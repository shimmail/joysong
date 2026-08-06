package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Translation

interface TranslationRepository {
    /**
     * Returns false when the text is already entirely in the app's target language.
     * Punctuation, numbers and emoji are ignored; mixed-language text is translated.
     */
    fun shouldTranslate(text: String): Boolean

    suspend fun translate(
        text: String,
        contentType: String = "general"
    ): Result<Translation>
}
