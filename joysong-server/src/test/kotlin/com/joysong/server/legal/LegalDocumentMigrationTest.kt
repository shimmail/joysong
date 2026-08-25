package com.joysong.server.legal

import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
import jakarta.persistence.EntityManager
import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
