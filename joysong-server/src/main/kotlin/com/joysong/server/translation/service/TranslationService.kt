package com.joysong.server.translation.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.translation.dto.TranslateTextRequest
import com.joysong.server.translation.dto.TranslationResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

@Service
class TranslationService(
    @Qualifier("llmRestTemplate") private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${openai.api-key:}") private val apiKey: String,
    @Value("\${openai.base-url:}") private val baseUrl: String,
    @Value("\${translation.model:gpt-5.5}") private val model: String,
    @Value("\${translation.provider:qwen}") private val provider: String = "qwen",
    @Value("\${translation.fallback-provider:openai}") private val fallbackProvider: String = "openai",
    @Value("\${translation.qwen.api-key:}") private val qwenApiKey: String = "",
    @Value("\${translation.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") private val qwenBaseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    @Value("\${translation.qwen.model:qwen3.7-flash}") private val qwenModel: String = "qwen3.7-flash"
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

        val translated = requestTranslation(text, targetLanguage, translationScope)
        synchronized(responseCache) {
            responseCache[cacheKey] = translated
        }
        return translated
    }

    private fun requestTranslation(text: String, targetLanguage: String, contentType: String): TranslationResponse {
        val selectedProvider = provider.trim().lowercase(Locale.ROOT)
        return try {
            translateWithProvider(selectedProvider, text, targetLanguage, contentType)
        } catch (error: Exception) {
            val selectedFallback = fallbackProvider.trim().lowercase(Locale.ROOT)
            if (selectedProvider != selectedFallback && selectedFallback in SUPPORTED_PROVIDERS) {
                logger.warn("Translation request via {} failed; falling back to {}: {}", selectedProvider, selectedFallback, error.message)
                translateWithProvider(selectedFallback, text, targetLanguage, contentType)
            } else {
                logger.warn("Translation request failed via {}: {}", selectedProvider, error.message)
                throw IllegalStateException("AI翻译暂时不可用，请稍后重试")
            }
        }
    }

    private fun translateWithProvider(
        selectedProvider: String,
        text: String,
        targetLanguage: String,
        contentType: String
    ): TranslationResponse = when (selectedProvider) {
        "qwen" -> requestQwenTranslation(text, targetLanguage)
        "openai" -> requestOpenAiTranslation(text, targetLanguage, contentType)
        else -> throw IllegalArgumentException("不支持的翻译服务提供方: $selectedProvider")
    }

    private fun requestQwenTranslation(text: String, targetLanguage: String): TranslationResponse {
        check(qwenApiKey.isNotBlank()) { "Qwen 翻译服务尚未配置" }
        val headers = HttpHeaders().apply {
            setBearerAuth(qwenApiKey)
            contentType = MediaType.APPLICATION_JSON
        }
        val body = mapOf(
            "model" to qwenModel,
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
        return try {
            val url = "${qwenBaseUrl.trimEnd('/')}/chat/completions"
            val response = restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
            val choice = (response.body?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
            val message = choice?.get("message") as? Map<*, *>
            val translatedText = message?.get("content")?.toString()?.trim().orEmpty()
            check(translatedText.isNotEmpty()) { "Qwen 翻译服务未返回有效内容" }
            TranslationResponse(
                translatedText = translatedText,
                detectedLanguage = "und",
                targetLanguage = targetLanguage,
                provider = "qwen"
            )
        } catch (error: Exception) {
            logger.warn("Qwen translation request failed: {}", error.message)
            throw IllegalStateException("Qwen 翻译暂时不可用")
        }
    }

    private fun requestOpenAiTranslation(text: String, targetLanguage: String, contentType: String): TranslationResponse {
        check(apiKey.isNotBlank()) { "AI翻译服务尚未配置，请稍后再试" }
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val headers = HttpHeaders().apply {
            setBearerAuth(apiKey)
            this.contentType = MediaType.APPLICATION_JSON
        }
        val systemPrompt = """
            Translate this medical-aesthetics community content to "$targetLanguage".
            Preserve meaning, paragraphs, emojis, @mentions, #hashtags, product/treatment names, numbers, dates, uncertainty, and HTML tags/attributes.
            Do not add advice or claims. Return only JSON: {"translatedText":"...","detectedLanguage":"BCP-47 tag or und"}.
            Scope: $contentType.
        """.trimIndent()
        val body = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to systemPrompt),
                mapOf("role" to "user", "content" to text)
            ),
            "temperature" to 0.1,
            "max_tokens" to translationMaxTokens(text),
            "stream" to false
        )

        return try {
            val response = restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
            val choice = (response.body?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
            val message = choice?.get("message") as? Map<*, *>
            val rawContent = message?.get("content")?.toString().orEmpty().trim()
            check(rawContent.isNotEmpty()) { "AI翻译服务未返回有效内容" }
            parseResponse(rawContent, targetLanguage).copy(provider = "openai")
        } catch (error: Exception) {
            throw IllegalStateException("AI翻译暂时不可用，请稍后重试")
        }
    }

    private fun parseResponse(rawContent: String, targetLanguage: String): TranslationResponse {
        val json = rawContent.substringAfter('{', "").substringBeforeLast('}', "")
            .takeIf(String::isNotBlank)
            ?.let { "{$it}" }
        val node = json?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        val translatedText = node?.path("translatedText")?.asText()?.trim().orEmpty()
            .ifBlank { rawContent.removePrefix("```").removeSuffix("```").trim() }
        check(translatedText.isNotEmpty()) { "AI翻译服务未返回有效内容" }
        val detectedLanguage = node?.path("detectedLanguage")?.asText()?.trim()
            ?.takeIf { it.matches(LANGUAGE_TAG_REGEX) }
            ?: "und"
        return TranslationResponse(
            translatedText = translatedText,
            detectedLanguage = detectedLanguage,
            targetLanguage = targetLanguage
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

    /** 为短文本减少不必要的模型输出预算，长日记仍保留足够的译文空间。 */
    private fun translationMaxTokens(text: String): Int =
        (text.length * 1.25).toInt().coerceIn(MIN_TRANSLATION_TOKENS, MAX_TRANSLATION_TOKENS)

    /** Qwen 翻译只返回译文，短文本不保留过多输出令牌。 */
    private fun qwenTranslationMaxTokens(text: String): Int =
        (text.length * 0.9).toInt().coerceIn(MIN_QWEN_TRANSLATION_TOKENS, MAX_QWEN_TRANSLATION_TOKENS)

    private companion object {
        const val MAX_TEXT_LENGTH = 12000
        const val MAX_CACHE_ENTRIES = 1000
        const val MIN_TRANSLATION_TOKENS = 512
        const val MAX_TRANSLATION_TOKENS = 5000
        const val MIN_QWEN_TRANSLATION_TOKENS = 128
        const val MAX_QWEN_TRANSLATION_TOKENS = 4096
        val USER_CONTENT_TYPES = setOf("diary", "comment", "message")
        val SUPPORTED_PROVIDERS = setOf("qwen", "openai")
        val LANGUAGE_TAG_REGEX = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
    }
}
