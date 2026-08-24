package com.joysong.server.translation.service

import com.joysong.server.translation.config.TranslationProperties
import com.joysong.server.translation.dto.TranslateTextRequest
import com.joysong.server.translation.dto.TranslationResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

@Service
class TranslationService(
    @Qualifier("translationRestTemplate") private val restTemplate: RestTemplate,
    private val translationProperties: TranslationProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val responseCache = object : LinkedHashMap<String, TranslationResponse>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslationResponse>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    fun translate(request: TranslateTextRequest): TranslationResponse {
        val text = request.text.trim()
        require(text.isNotEmpty()) { "待翻译内容不能为空" }
        require(text.length <= MAX_TEXT_LENGTH) { "单次翻译内容不能超过${MAX_TEXT_LENGTH}个字符" }

        val targetLanguage = normalizeLanguageTag(request.targetLanguage)
        val translationScope = unifiedTranslationScope(request.contentType)
        val cacheKey = hash("$targetLanguage\u0000$translationScope\u0000$text")
        synchronized(responseCache) {
            responseCache[cacheKey]?.let { return it.copy(cached = true) }
        }

        val translated = requestTranslation(text, targetLanguage)
        synchronized(responseCache) {
            responseCache[cacheKey] = translated
        }
        return translated
    }

    private fun requestTranslation(text: String, targetLanguage: String): TranslationResponse {
        return try {
            requestQwenTranslation(text, targetLanguage)
        } catch (error: Exception) {
            logTranslationFailure(error)
            throw IllegalStateException("AI翻译暂时不可用，请稍后重试")
        }
    }

    private fun requestQwenTranslation(text: String, targetLanguage: String): TranslationResponse {
        val qwen = translationProperties.qwen
        check(qwen.apiKey.isNotBlank()) { "Qwen 翻译服务尚未配置" }
        val headers = HttpHeaders().apply {
            setBearerAuth(qwen.apiKey)
            contentType = MediaType.APPLICATION_JSON
        }
        val body = mapOf(
            "model" to qwen.model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to "Translate into ${Locale.forLanguageTag(targetLanguage).getDisplayLanguage(Locale.ENGLISH)}. Preserve meaning, line breaks, emoji, @mentions, #hashtags, names, numbers and HTML. Output only the translation."
                ),
                mapOf("role" to "user", "content" to text)
            ),
            "enable_thinking" to false,
            "temperature" to 0,
            "max_tokens" to qwenTranslationMaxTokens(text),
            "stream" to false
        )
        val url = "${qwen.baseUrl.trimEnd('/')}/chat/completions"
        val response = restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
        val choice = (response.body?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
        val message = choice?.get("message") as? Map<*, *>
        val translatedText = message?.get("content")?.toString()?.trim().orEmpty()
        check(translatedText.isNotEmpty()) { "Qwen 翻译服务未返回有效内容" }
        return TranslationResponse(
            translatedText = translatedText,
            detectedLanguage = "und",
            targetLanguage = targetLanguage,
            provider = translationProperties.provider
        )
    }

    private fun logTranslationFailure(error: Exception) {
        val responseError = generateSequence(error as Throwable?) { it.cause }
            .filterIsInstance<RestClientResponseException>()
            .firstOrNull()
        val category = when {
            responseError != null -> "HTTP_ERROR"
            error is ResourceAccessException -> "NETWORK_ERROR"
            else -> "INVALID_RESPONSE"
        }
        val status = responseError?.statusCode?.value()?.takeIf { it in 100..599 }
        val httpStatus = status?.toString() ?: "none"
        val errorCode = when {
            status in setOf(401, 403) -> "AUTH"
            status == 429 -> "RATE_LIMIT"
            status != null && status in 400..499 -> "INVALID_REQUEST"
            status != null && status in 500..599 -> "UPSTREAM_5XX"
            error is ResourceAccessException -> "NETWORK"
            else -> "INVALID_RESPONSE"
        }
        logger.warn(
            "TRANSLATION_PROVIDER category={} httpStatus={} errorCode={}",
            category,
            httpStatus,
            errorCode
        )
    }

    private fun normalizeLanguageTag(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.matches(LANGUAGE_TAG_REGEX)) { "目标语言需使用BCP 47格式" }
        val locale = Locale.forLanguageTag(trimmed)
        require(locale.language.isNotBlank() && locale.language != "und") { "目标语言格式不正确" }
        return locale.toLanguageTag()
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * 评论、回复、私信和日记正文共用同一翻译语境与缓存。
     * 调用方仍保留原内容类型，便于前端定位和展示。
     */
    private fun unifiedTranslationScope(contentType: String): String =
        if (contentType in USER_CONTENT_TYPES) "user_content" else contentType

    /** Qwen 翻译只返回译文，短文本不保留过多输出令牌。 */
    private fun qwenTranslationMaxTokens(text: String): Int =
        (text.length * 0.9).toInt().coerceIn(MIN_QWEN_TRANSLATION_TOKENS, MAX_QWEN_TRANSLATION_TOKENS)

    private companion object {
        const val MAX_TEXT_LENGTH = 12000
        const val MAX_CACHE_ENTRIES = 1000
        const val MIN_QWEN_TRANSLATION_TOKENS = 128
        const val MAX_QWEN_TRANSLATION_TOKENS = 4096
        val USER_CONTENT_TYPES = setOf("diary", "comment", "message")
        val LANGUAGE_TAG_REGEX = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    }
}
