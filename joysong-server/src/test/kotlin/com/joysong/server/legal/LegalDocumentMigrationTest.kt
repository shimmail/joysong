package com.joysong.server.legal

import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import com.joysong.server.legal.dto.LegalDocumentLocaleInput
import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.dto.UpdateLegalDocumentDraftRequest
import com.joysong.server.legal.service.LegalDocumentHtmlSanitizer
import com.joysong.server.legal.service.LegalDocumentService
import com.joysong.server.legal.service.LegalDocumentConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import jakarta.persistence.EntityManager
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("mysql-integration")
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LegalDocumentMigrationTest {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var releaseRepository: LegalDocumentReleaseRepository

    @Autowired
    private lateinit var contentRepository: LegalDocumentContentRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `fresh database contains legal release constraints`() {
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_releases'",
                Int::class.java
            )
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_contents'",
                Int::class.java
            )
        )
    }

    @Test
    fun `legal schema enforces locale unique foreign key and hash contracts`() {
        insertRelease("draft-1", "USER_AGREEMENT", 1, "DRAFT")
        insertContent("content-zh", "draft-1", "zh-CN")

        assertThrows<DataIntegrityViolationException> {
            insertContent("content-duplicate-locale", "draft-1", "zh-CN")
        }
        assertThrows<DataIntegrityViolationException> {
            insertContent("content-invalid-locale", "draft-1", "fr-FR")
        }
        assertThrows<DataIntegrityViolationException> {
            insertContent("content-missing-release", "missing-release", "en-US")
        }
        assertThrows<DataIntegrityViolationException> {
            insertRelease("draft-2", "USER_AGREEMENT", 2, "DRAFT")
        }
        assertThrows<DataIntegrityViolationException> {
            insertRelease("duplicate-version", "USER_AGREEMENT", 1, "SUPERSEDED")
        }
        insertRelease("published-1", "PRIVACY_POLICY", 1, "PUBLISHED")
        assertThrows<DataIntegrityViolationException> {
            insertRelease("published-2", "PRIVACY_POLICY", 2, "PUBLISHED")
        }
        assertThrows<DataIntegrityViolationException> {
            insertRelease("invalid-type", "TERMS", 1, "SUPERSEDED")
        }
        assertThrows<DataIntegrityViolationException> {
            insertRelease("invalid-status", "PRIVACY_POLICY", 3, "ARCHIVED")
        }

        assertEquals(
            64,
            jdbc.queryForObject(
                """
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'legal_document_contents'
                  AND column_name = 'content_sha256'
                """.trimIndent(),
                Int::class.java
            )
        )
        assertEquals(
            "char",
            jdbc.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'legal_document_contents'
                  AND column_name = 'content_sha256'
                """.trimIndent(),
                String::class.java
            )
        )
    }

    @Test
    fun `JPA stores and reads locale tags for both legal document languages`() {
        releaseRepository.saveAndFlush(
            LegalDocumentReleaseEntity(
                id = "jpa-release",
                documentType = LegalDocumentType.PRIVACY_POLICY,
                version = 1,
                status = LegalDocumentStatus.DRAFT,
                createdBy = "admin",
                updatedBy = "admin"
            )
        )
        contentRepository.saveAllAndFlush(
            listOf(
                LegalDocumentContentEntity(
                    id = "jpa-content-zh",
                    releaseId = "jpa-release",
                    locale = LegalDocumentLocale.ZH_CN,
                    title = "中文",
                    contentHtml = "<p>zh</p>",
                    contentSha256 = "a".repeat(64)
                ),
                LegalDocumentContentEntity(
                    id = "jpa-content-en",
                    releaseId = "jpa-release",
                    locale = LegalDocumentLocale.EN_US,
                    title = "English",
                    contentHtml = "<p>en</p>",
                    contentSha256 = "b".repeat(64)
                )
            )
        )
        entityManager.clear()

        assertEquals(
            listOf("en-US", "zh-CN"),
            jdbc.queryForList(
                "SELECT locale FROM legal_document_contents WHERE release_id = 'jpa-release' ORDER BY locale",
                String::class.java
            )
        )
        assertEquals(
            listOf(LegalDocumentLocale.EN_US, LegalDocumentLocale.ZH_CN),
            contentRepository.findAllByReleaseIdOrderByLocaleAsc("jpa-release").map { it.locale }
        )
    }

    @Test
    fun `publish switches active release without unique constraint conflict and returns database lock version`() {
        val previous = releaseRepository.saveAndFlush(legalRelease("published-1", 1, LegalDocumentStatus.PUBLISHED))
        val draft = releaseRepository.saveAndFlush(legalRelease("draft-2", 2, LegalDocumentStatus.DRAFT))
        contentRepository.saveAllAndFlush(bilingualContents(draft.id))
        val requestLockVersion = draft.lockVersion
        entityManager.clear()

        val published = legalService().publish(draft.id, "admin-2", PublishLegalDocumentRequest(requestLockVersion))

        entityManager.flush()
        entityManager.clear()
        val databaseDraft = releaseRepository.findById(draft.id).orElseThrow()
        val databasePrevious = releaseRepository.findById(previous.id).orElseThrow()
        assertEquals(LegalDocumentStatus.PUBLISHED, databaseDraft.status)
        assertEquals(LegalDocumentStatus.SUPERSEDED, databasePrevious.status)
        assertEquals(databaseDraft.lockVersion, published.lockVersion)
        assertEquals(1L, published.lockVersion)
    }

    @Test
    fun `update draft returns the JPA incremented lock version`() {
        val draft = releaseRepository.saveAndFlush(legalRelease("draft-update", 1, LegalDocumentStatus.DRAFT))
        contentRepository.saveAllAndFlush(bilingualContents(draft.id))
        val requestLockVersion = draft.lockVersion
        entityManager.clear()

        val updated = legalService().updateDraft(
            draft.id,
            "admin-2",
            UpdateLegalDocumentDraftRequest(
                requestLockVersion,
                "Updated",
                mapOf(
                    "zh-CN" to LegalDocumentLocaleInput("中文标题", "<p>中文正文</p>"),
                    "en-US" to LegalDocumentLocaleInput("English title", "<p>English body</p>")
                )
            )
        )

        entityManager.flush()
        entityManager.clear()
        val databaseDraft = releaseRepository.findById(draft.id).orElseThrow()
        assertEquals(databaseDraft.lockVersion, updated.lockVersion)
        assertEquals(1L, updated.lockVersion)
    }

    @Test
    fun `concurrent initial privacy drafts leave exactly one active draft`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = (1..2).map { index ->
                executor.submit<Result<String>> {
                    val transaction = TransactionTemplate(transactionManager)
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "并发草稿测试未能同步起跑" }
                    runCatching {
                        transaction.execute {
                            legalService().createDraft(LegalDocumentType.PRIVACY_POLICY, "admin-$index").id
                        } ?: error("草稿事务未返回结果")
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发草稿任务未准备完成")
            start.countDown()
            val outcomes = attempts.map { it.get(30, TimeUnit.SECONDS) }
            val failures = outcomes.mapNotNull(Result<String>::exceptionOrNull)

            assertEquals(1, outcomes.count(Result<String>::isSuccess))
            assertEquals(1, failures.size)
            assertTrue(
                failures.single().hasCause(LegalDocumentConflictException::class.java),
                "失败方必须因已有草稿或数据库唯一约束失败"
            )
            assertEquals(
                1L,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM legal_document_releases WHERE document_type = 'PRIVACY_POLICY' AND status = 'DRAFT'",
                    Long::class.java
                )
            )
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "并发草稿执行器未停止")
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbc.update("DELETE FROM legal_document_contents WHERE release_id IN (SELECT id FROM legal_document_releases WHERE document_type = 'PRIVACY_POLICY')")
                jdbc.update("DELETE FROM legal_document_releases WHERE document_type = 'PRIVACY_POLICY'")
            }
        }
    }

    private fun legalService() = LegalDocumentService(
        releaseRepository,
        contentRepository,
        LegalDocumentHtmlSanitizer()
    )

    private fun legalRelease(id: String, version: Int, status: LegalDocumentStatus) = LegalDocumentReleaseEntity(
        id = id,
        documentType = LegalDocumentType.USER_AGREEMENT,
        version = version,
        status = status,
        publishedAt = if (status == LegalDocumentStatus.PUBLISHED) java.time.LocalDateTime.now() else null,
        publishedBy = if (status == LegalDocumentStatus.PUBLISHED) "admin-1" else null,
        createdBy = "admin-1",
        updatedBy = "admin-1"
    )

    private fun bilingualContents(releaseId: String) = listOf(
        LegalDocumentContentEntity(
            "$releaseId-en", releaseId, LegalDocumentLocale.EN_US, "English", "<p>English body</p>", "a".repeat(64)
        ),
        LegalDocumentContentEntity(
            "$releaseId-zh", releaseId, LegalDocumentLocale.ZH_CN, "中文", "<p>中文正文</p>", "b".repeat(64)
        )
    )

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { it.cause }.any(type::isInstance)

    private fun insertRelease(id: String, type: String, version: Int, status: String) {
        jdbc.update(
            """
            INSERT INTO legal_document_releases
                (id, document_type, version, status, created_by, updated_by)
            VALUES (?, ?, ?, ?, 'admin', 'admin')
            """.trimIndent(),
            id,
            type,
            version,
            status
        )
    }

    private fun insertContent(id: String, releaseId: String, locale: String) {
        jdbc.update(
            """
            INSERT INTO legal_document_contents
                (id, release_id, locale, content_html, content_sha256)
            VALUES (?, ?, ?, '<p>content</p>', ?)
            """.trimIndent(),
            id,
            releaseId,
            locale,
            "c".repeat(64)
        )
    }

    companion object {
        @JvmField
        val mysql = MySqlLegalContainer("mysql:8.0.39")
            .withDatabaseName("myapp_worktree_legal_documents")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) {
            validateDatabaseName()
            if (!mysql.isRunning) mysql.start()
            printDatabaseConnection()
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.datasource.driver-class-name") { "com.mysql.cj.jdbc.Driver" }
        }

        @JvmStatic
        @AfterAll
        fun stopContainer() {
            if (mysql.isRunning) mysql.stop()
        }

        private fun validateDatabaseName() {
            require(mysql.databaseName == expectedDatabaseName())
            require(mysql.databaseName.startsWith("myapp_worktree_"))
        }

        private fun printDatabaseConnection() {
            println("Migration database host=${mysql.host}:${mysql.getMappedPort(3306)}, database=${mysql.databaseName}")
        }

        private fun expectedDatabaseName(): String {
            val worktree = generateSequence(currentDirectory()) { it.parent }
                .firstOrNull { Files.exists(it.resolve(".git")) }
                ?: error("Unable to find the current Git worktree from ${currentDirectory()}")
            val worktreeId = worktree.fileName.toString()
                .removePrefix("worktree_")
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .trim('_')
                .lowercase()
            return "myapp_worktree_$worktreeId"
        }

        private fun currentDirectory(): Path =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
    }
}

class MySqlLegalContainer(image: String) : MySQLContainer<MySqlLegalContainer>(image)
