package com.joysong.server.legal.service

import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import java.time.LocalDateTime

@SpringJUnitConfig(LegalDocumentServiceCacheTest.CacheConfig::class)
class LegalDocumentServiceCacheTest {
    @Autowired
    private lateinit var service: LegalDocumentService

    @Autowired
    private lateinit var releaseRepository: LegalDocumentReleaseRepository

    @Autowired
    private lateinit var contentRepository: LegalDocumentContentRepository

    @Test
    fun `proxied public lookup caches hits and publish evicts the cached entry`() {
        val previous = release("published-1", LegalDocumentStatus.PUBLISHED)
        val draft = release("draft-2", LegalDocumentStatus.DRAFT)
        every { releaseRepository.findFirstByDocumentTypeAndStatus(LegalDocumentType.USER_AGREEMENT, LegalDocumentStatus.PUBLISHED) } returns previous
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(previous.id) } returns contents(previous.id)

        assertEquals("English", service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title)
        assertEquals("English", service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title)
        verify(exactly = 1) {
            releaseRepository.findFirstByDocumentTypeAndStatus(LegalDocumentType.USER_AGREEMENT, LegalDocumentStatus.PUBLISHED)
        }

        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns contents(draft.id)
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns listOf(draft, previous)
        every { releaseRepository.saveAndFlush(previous) } answers { previous }
        every { releaseRepository.saveAndFlush(draft) } answers { draft }

        service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
        service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)

        verify(exactly = 2) {
            releaseRepository.findFirstByDocumentTypeAndStatus(LegalDocumentType.USER_AGREEMENT, LegalDocumentStatus.PUBLISHED)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    class CacheConfig {
        @Bean fun cacheManager() = ConcurrentMapCacheManager("legalDocuments")
        @Bean fun releaseRepository(): LegalDocumentReleaseRepository = mockk()
        @Bean fun contentRepository(): LegalDocumentContentRepository = mockk()
        @Bean fun sanitizer() = LegalDocumentHtmlSanitizer()
        @Bean fun service(
            releaseRepository: LegalDocumentReleaseRepository,
            contentRepository: LegalDocumentContentRepository,
            sanitizer: LegalDocumentHtmlSanitizer
        ) = LegalDocumentService(releaseRepository, contentRepository, sanitizer)
    }

    private fun release(id: String, status: LegalDocumentStatus) = LegalDocumentReleaseEntity(
        id = id,
        documentType = LegalDocumentType.USER_AGREEMENT,
        version = if (status == LegalDocumentStatus.PUBLISHED) 1 else 2,
        status = status,
        publishedAt = if (status == LegalDocumentStatus.PUBLISHED) LocalDateTime.now() else null,
        createdBy = "admin-1",
        updatedBy = "admin-1"
    )

    private fun contents(releaseId: String) = listOf(
        LegalDocumentContentEntity("$releaseId-en", releaseId, LegalDocumentLocale.EN_US, "English", "<p>Body</p>", "a".repeat(64)),
        LegalDocumentContentEntity("$releaseId-zh", releaseId, LegalDocumentLocale.ZH_CN, "中文", "<p>正文</p>", "b".repeat(64))
    )
}
