package com.joysong.server.translation.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.translation.dto.TranslateTextRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate

class TranslationServiceTest {
    private val restTemplate = RestTemplate()
    private val server = MockRestServiceServer.createServer(restTemplate)
    private val service = TranslationService(
        restTemplate = restTemplate,
        objectMapper = jacksonObjectMapper(),
        apiKey = "test-key",
        baseUrl = "https://example.test/v1",
        model = "test-model",
        provider = "openai"
    )

    @Test
    fun `translates structured response and reuses cache`() {
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"{\"translatedText\":\"Recovery is going well ✨\",\"detectedLanguage\":\"zh-CN\"}"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        val request = TranslateTextRequest("恢复得不错 ✨", "en-US", "diary")
        val first = service.translate(request)
        val second = service.translate(request)

        assertEquals("Recovery is going well ✨", first.translatedText)
        assertEquals("zh-CN", first.detectedLanguage)
        assertEquals("en-US", first.targetLanguage)
        assertFalse(first.cached)
        assertTrue(second.cached)
        server.verify()
    }

    @Test
    fun `shares cache across diary comment reply and message translations`() {
        server.expect(requestTo("https://example.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"choices":[{"message":{"content":"{\"translatedText\":\"Hello\",\"detectedLanguage\":\"zh-CN\"}"}}]}""",
                    MediaType.APPLICATION_JSON
                )
            )

        service.translate(TranslateTextRequest("你好", "en-US", "diary"))
        val fromReply = service.translate(TranslateTextRequest("你好", "en-US", "comment"))
        val fromMessage = service.translate(TranslateTextRequest("你好", "en-US", "message"))

        assertTrue(fromReply.cached)
        assertTrue(fromMessage.cached)
        server.verify()
    }

    @Test
    fun `uses qwen flash with concise translation prompt`() {
        val qwenRestTemplate = RestTemplate()
        val qwenServer = MockRestServiceServer.createServer(qwenRestTemplate)
        val qwenService = TranslationService(
            restTemplate = qwenRestTemplate,
            objectMapper = jacksonObjectMapper(),
            apiKey = "",
            baseUrl = "https://unused.test/v1",
            model = "unused-model",
            provider = "qwen",
            fallbackProvider = "none",
            qwenApiKey = "qwen-key",
            qwenBaseUrl = "https://qwen.test/v1",
            qwenModel = "qwen3.7-flash"
        )
        qwenServer.expect(requestTo("https://qwen.test/v1/chat/completions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("qwen3.7-flash"))
            .andExpect(jsonPath("$.messages[0].role").value("system"))
            .andExpect(jsonPath("$.messages[1].role").value("user"))
            .andExpect(jsonPath("$.enable_thinking").value(false))
            .andExpect(jsonPath("$.temperature").value(0))
            .andRespond(withSuccess("""{"choices":[{"message":{"content":"Hello"}}]}""", MediaType.APPLICATION_JSON))

        val response = qwenService.translate(TranslateTextRequest("你好", "en-US", "comment"))

        assertEquals("Hello", response.translatedText)
        assertEquals("qwen", response.provider)
        qwenServer.verify()
    }

    @Test
    fun `rejects invalid language tag`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.translate(TranslateTextRequest("hello", "not_a_language", "general"))
        }
    }
}
