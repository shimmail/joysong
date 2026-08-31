package com.joysong.server.diary.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.diary.entity.DiaryShareEntity
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.diary.repository.DiaryShareRepository
import com.joysong.server.diary.service.DiaryShareService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.filter.ForwardedHeaderFilter
import java.util.Optional

class DiaryShareProxyUrlTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `create share uses the forwarded request origin when no base URL is configured`() {
        val response = createShare(
            configuredBaseUrl = "",
            forwardedHost = "proxy.example.test"
        )
        val data = objectMapper.readTree(response).path("data")

        assertEquals(
            "https://proxy.example.test/s/diary/${data.path("token").asText()}",
            data.path("shareUrl").asText()
        )
    }

    @Test
    fun `configured base URL overrides the forwarded request origin`() {
        val response = createShare(
            configuredBaseUrl = "https://share.example.test/s/diary/",
            forwardedHost = "spoofed.example.test"
        )
        val data = objectMapper.readTree(response).path("data")

        assertEquals(
            "https://share.example.test/s/diary/${data.path("token").asText()}",
            data.path("shareUrl").asText()
        )
    }

    @Test
    fun `request origin fallback must be explicitly enabled`() {
        val error = assertThrows<Exception> {
            createShare(
                configuredBaseUrl = "",
                forwardedHost = "spoofed.example.test",
                requestOriginFallbackEnabled = false,
            )
        }

        assertTrue(error.causeChain().contains("request-origin fallback is disabled"))
    }

    @Test
    fun `invalid configured base URL is rejected instead of shared`() {
        val error = assertThrows<Exception> {
            createShare(
                configuredBaseUrl = "javascript://share.example.test/s/diary/",
                forwardedHost = "proxy.example.test",
            )
        }

        assertTrue(error.causeChain().contains("absolute HTTP(S) URL"))
    }

    @Test
    fun `raw forwarded headers are ignored without the trusted proxy adapter`() {
        val response = createShare(
            configuredBaseUrl = "",
            forwardedHost = "spoofed.example.test",
            trustForwardedHeaders = false,
        )
        val data = objectMapper.readTree(response).path("data")

        assertEquals(
            "http://localhost/s/diary/${data.path("token").asText()}",
            data.path("shareUrl").asText()
        )
    }

    private fun createShare(
        configuredBaseUrl: String,
        forwardedHost: String,
        requestOriginFallbackEnabled: Boolean = true,
        trustForwardedHeaders: Boolean = true,
    ): String =
        mvc(configuredBaseUrl, requestOriginFallbackEnabled, trustForwardedHeaders).perform(
            post("/api/diaries/diary-1/share")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", forwardedHost)
                .principal(UsernamePasswordAuthenticationToken("user-1", "unused"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

    private fun mvc(
        configuredBaseUrl: String,
        requestOriginFallbackEnabled: Boolean,
        trustForwardedHeaders: Boolean,
    ): MockMvc {
        val diaryRepository = mockk<DiaryRepository>()
        val shareRepository = mockk<DiaryShareRepository>()
        every { diaryRepository.findById("diary-1") } returns Optional.of(
            DiaryEntity(
                id = "diary-1",
                title = "Proxy share",
                userId = "user-1",
                authorName = "Author",
                status = "published"
            )
        )
        every { shareRepository.findByDiaryIdAndRevokedAtIsNull("diary-1") } returns null
        every { shareRepository.save(any<DiaryShareEntity>()) } answers { firstArg<DiaryShareEntity>() }

        val controller = DiaryShareController(
            DiaryShareService(
                diaryRepository,
                shareRepository,
                configuredBaseUrl,
                shareRequestOriginFallbackEnabled = requestOriginFallbackEnabled,
            )
        )
        val builder = MockMvcBuilders.standaloneSetup(controller)
        if (trustForwardedHeaders) {
            builder.addFilters<StandaloneMockMvcBuilder>(ForwardedHeaderFilter())
        }
        return builder.build()
    }

    private fun Throwable.causeChain(): String = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
}
