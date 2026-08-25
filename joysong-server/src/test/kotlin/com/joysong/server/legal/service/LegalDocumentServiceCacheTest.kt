package com.joysong.server.legal.service

import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.dto.PublicLegalDocumentView
import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import io.mockk.every
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.CacheManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

@SpringJUnitConfig(LegalDocumentServiceCacheTest.CacheConfig::class)
class LegalDocumentServiceCacheTest {
    @Autowired
    private lateinit var service: LegalDocumentService

    @Autowired
    private lateinit var releaseRepository: LegalDocumentReleaseRepository

    @Autowired
    private lateinit var contentRepository: LegalDocumentContentRepository

    @Autowired
    private lateinit var cacheManager: CacheManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var cacheInvalidator: LegalDocumentCacheInvalidator

    @BeforeEach
    fun resetTestState() {
        clearMocks(releaseRepository, contentRepository, answers = false)
        requireNotNull(cacheManager.getCache("legalDocuments")).clear()
    }

    @Test
    fun `proxied public lookup caches hits`() {
        val previous = release("published-1", LegalDocumentStatus.PUBLISHED)
        every { releaseRepository.findFirstByDocumentTypeAndStatus(LegalDocumentType.USER_AGREEMENT, LegalDocumentStatus.PUBLISHED) } returns previous
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(previous.id) } returns contents(previous.id)

        assertEquals("English", service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title)
        assertEquals("English", service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title)
        verify(exactly = 1) {
            releaseRepository.findFirstByDocumentTypeAndStatus(LegalDocumentType.USER_AGREEMENT, LegalDocumentStatus.PUBLISHED)
        }
    }

    @Test
    fun `publish keeps both locale cache entries until commit then evicts them`() {
        val cache = requireNotNull(cacheManager.getCache("legalDocuments"))
        val previousZhKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.ZH_CN)
        val previousEnKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)
        cache.put(previousZhKey, "old-zh")
        cache.put(previousEnKey, "old-en")
        val draft = release("draft-2", LegalDocumentStatus.DRAFT)
        stubPublish(draft)

        TransactionTemplate(transactionManager).executeWithoutResult {
            service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
            assertEquals("old-zh", cache.get(previousZhKey)?.get())
            assertEquals("old-en", cache.get(previousEnKey)?.get())
        }

        assertNull(cache.get(previousZhKey))
        assertNull(cache.get(previousEnKey))
        assertNotEquals(
            previousZhKey,
            cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.ZH_CN)
        )
        assertNotEquals(
            previousEnKey,
            cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)
        )
    }

    @Test
    fun `publish rollback preserves both locale cache entries`() {
        val cache = requireNotNull(cacheManager.getCache("legalDocuments"))
        val zhKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.ZH_CN)
        val enKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)
        cache.put(zhKey, "old-zh")
        cache.put(enKey, "old-en")
        val draft = release("draft-rollback", LegalDocumentStatus.DRAFT)
        stubPublish(draft)

        assertThrows<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
                assertEquals("old-zh", cache.get(zhKey)?.get())
                assertEquals("old-en", cache.get(enKey)?.get())
                error("force rollback")
            }
        }

        assertEquals(zhKey, cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.ZH_CN))
        assertEquals(enKey, cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US))
        assertEquals("old-zh", cache.get(zhKey)?.get())
        assertEquals("old-en", cache.get(enKey)?.get())
    }

    @Test
    fun `late cache put from a pre-commit read cannot hide the newly published release`() {
        val cache = requireNotNull(cacheManager.getCache("legalDocuments"))
        val oldRelease = release("published-old", LegalDocumentStatus.PUBLISHED)
        val draft = release("draft-new", LegalDocumentStatus.DRAFT)
        val publishedRelease = AtomicReference(oldRelease)
        val oldReleaseFetched = CountDownLatch(1)
        val allowOldReaderToReturn = CountDownLatch(1)
        val reader = Executors.newSingleThreadExecutor()
        val oldCacheKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)

        every {
            releaseRepository.findFirstByDocumentTypeAndStatus(
                LegalDocumentType.USER_AGREEMENT,
                LegalDocumentStatus.PUBLISHED
            )
        } answers {
            publishedRelease.get().also { captured ->
                if (captured.id == oldRelease.id) {
                    oldReleaseFetched.countDown()
                    check(allowOldReaderToReturn.await(5, TimeUnit.SECONDS)) {
                        "timed out waiting to release the old cache-miss reader"
                    }
                }
            }
        }
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(oldRelease.id) } returns
            contents(oldRelease.id, englishTitle = "Old English")
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns
            contents(draft.id, englishTitle = "New English")
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.saveAllAndFlush(any<List<LegalDocumentContentEntity>>()) } answers { firstArg() }
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns
            listOf(draft, oldRelease)
        every { releaseRepository.saveAndFlush(oldRelease) } answers { oldRelease }
        every { releaseRepository.saveAndFlush(draft) } answers { draft }

        try {
            val lateOldRead = reader.submit<PublicLegalDocumentView?> {
                service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)
            }
            assertTrue(oldReleaseFetched.await(5, TimeUnit.SECONDS), "old reader did not reach the repository")

            TransactionTemplate(transactionManager).executeWithoutResult {
                service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
                publishedRelease.set(draft)
            }
            val newCacheKey = cacheInvalidator.cacheKey(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)
            assertNotEquals(oldCacheKey, newCacheKey)

            allowOldReaderToReturn.countDown()
            assertEquals("Old English", lateOldRead.get(5, TimeUnit.SECONDS)?.title)
            assertEquals("Old English", (cache.get(oldCacheKey)?.get() as PublicLegalDocumentView).title)
            assertNull(cache.get(newCacheKey))

            assertEquals(
                "New English",
                service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title
            )
            assertEquals("New English", (cache.get(newCacheKey)?.get() as PublicLegalDocumentView).title)
            assertEquals(
                "New English",
                service.findPublished(LegalDocumentType.USER_AGREEMENT, LegalDocumentLocale.EN_US)?.title
            )
            verify(exactly = 2) {
                releaseRepository.findFirstByDocumentTypeAndStatus(
                    LegalDocumentType.USER_AGREEMENT,
                    LegalDocumentStatus.PUBLISHED
                )
            }
        } finally {
            allowOldReaderToReturn.countDown()
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(5, TimeUnit.SECONDS), "cache reader executor did not stop")
        }
    }

    private fun stubPublish(draft: LegalDocumentReleaseEntity) {
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns contents(draft.id)
        every { contentRepository.saveAllAndFlush(any<List<LegalDocumentContentEntity>>()) } answers { firstArg() }
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns listOf(draft)
        every { releaseRepository.saveAndFlush(draft) } answers { draft }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching
    @EnableTransactionManagement
    class CacheConfig {
        @Bean fun cacheManager() = ConcurrentMapCacheManager("legalDocuments")
        @Bean fun dataSource(): DataSource =
            DriverManagerDataSource("jdbc:h2:mem:myapp_worktree_legal_documents;DB_CLOSE_DELAY=-1")
        @Bean fun transactionManager(dataSource: DataSource): PlatformTransactionManager = DataSourceTransactionManager(dataSource)
        @Bean fun releaseRepository(): LegalDocumentReleaseRepository = mockk()
        @Bean fun contentRepository(): LegalDocumentContentRepository = mockk()
        @Bean fun sanitizer() = LegalDocumentHtmlSanitizer()
        @Bean fun legalDocumentCacheInvalidator(cacheManager: CacheManager) = LegalDocumentCacheInvalidator(cacheManager)
        @Bean fun service(
            releaseRepository: LegalDocumentReleaseRepository,
            contentRepository: LegalDocumentContentRepository,
            sanitizer: LegalDocumentHtmlSanitizer,
            cacheInvalidator: LegalDocumentCacheInvalidator
        ) = LegalDocumentService(releaseRepository, contentRepository, sanitizer, cacheInvalidator)
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

    private fun contents(releaseId: String, englishTitle: String = "English") = listOf(
        LegalDocumentContentEntity("$releaseId-en", releaseId, LegalDocumentLocale.EN_US, englishTitle, "<p>Body</p>", "a".repeat(64)),
        LegalDocumentContentEntity("$releaseId-zh", releaseId, LegalDocumentLocale.ZH_CN, "中文", "<p>正文</p>", "b".repeat(64))
    )
}
