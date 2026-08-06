package com.joysong.app.data.repository

import android.content.Context
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.TranslateTextRequestDto
import com.joysong.app.domain.model.Translation
import com.joysong.app.domain.repository.TranslationRepository
import com.joysong.app.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslationRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    @ApplicationContext private val context: Context
) : TranslationRepository {
    override fun shouldTranslate(text: String): Boolean {
        val language = LocaleHelper.getLocale(context)
        return !isEntirelyTargetLanguage(text, language)
    }

    override suspend fun translate(
        text: String,
        contentType: String
    ): Result<Translation> = runCatching {
        val response = apiService.translateText(
            TranslateTextRequestDto(
                text = text,
                targetLanguage = LocaleHelper.getLocale(context),
                contentType = contentType
            )
        )
        val data = response.data
        if (response.code != 200 || data == null) {
            throw IllegalStateException(response.message.ifBlank { "AI翻译暂时不可用" })
        }
        Translation(
            translatedText = data.translatedText,
            detectedLanguage = data.detectedLanguage,
            targetLanguage = data.targetLanguage,
            cached = data.cached
        )
    }

    private fun isEntirelyTargetLanguage(text: String, targetLanguage: String): Boolean {
        var targetLetterCount = 0
        var otherLetterCount = 0
        val content = text.replace(HTML_TAG_REGEX, "")

        for (char in content) {
            if (!Character.isLetter(char)) continue
            val isTargetLetter = when (targetLanguage) {
                LocaleHelper.LANG_CHINESE -> Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN
                LocaleHelper.LANG_ENGLISH -> Character.UnicodeScript.of(char.code) == Character.UnicodeScript.LATIN
                else -> false
            }
            if (isTargetLetter) targetLetterCount++ else otherLetterCount++
        }

        // Do not skip symbols-only content: it has no reliable source language.
        return targetLetterCount > 0 && otherLetterCount == 0
    }

    private companion object {
        val HTML_TAG_REGEX = Regex("<[^>]*>")
    }
}
