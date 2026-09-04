package com.joysong.server.migration

import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Tag("mysql-integration")
@Testcontainers
class PrivateFileStorageProviderUpgradeMySqlIntegrationTest {

    @Test
    fun `V39 preserves existing private file data and defaults its provider to local private`() {
        WorktreeTestDatabase.validateAndPrint(mysql)
        val dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
        val jdbc = JdbcTemplate(dataSource)
        migrate(target = "38")
        jdbc.update(
            "INSERT INTO users (id, password_hash, nickname) VALUES (?, ?, ?)",
            OWNER_ID,
            "test-password-hash",
            "Existing private file owner",
        )
        jdbc.update(
            """
            INSERT INTO private_files
                (id, owner_user_id, purpose, storage_key, original_name, content_type,
                 size_bytes, sha256, status)
            VALUES (?, ?, 'ID_CARD_FRONT', ?, 'legacy.png', 'image/png', ?, ?, 'ACTIVE')
            """.trimIndent(),
            FILE_ID,
            OWNER_ID,
            STORAGE_KEY,
            SIZE_BYTES,
            SHA256,
        )
        jdbc.update(
            """
            INSERT INTO user_media_assets
                (id, owner_user_id, storage_key, asset_type, storage_provider, delete_status)
            VALUES (?, ?, ?, 'IDENTITY', 'LOCAL_PRIVATE', 'ACTIVE')
            """.trimIndent(),
            "v39-existing-media-asset",
            OWNER_ID,
            STORAGE_KEY,
        )
        val before = legacyRow(jdbc)

        migrate(target = "39")

        assertEquals(before.copy(storageProvider = "LOCAL_PRIVATE"), migratedRow(jdbc))
        assertEquals(
            "39",
            jdbc.queryForObject(
                "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1",
                String::class.java,
            ),
        )
        assertEquals(
            "LOCAL_PRIVATE",
            jdbc.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'private_files'
                  AND column_name = 'storage_provider'
                """.trimIndent(),
                String::class.java,
            ),
        )
        assertEquals(
            listOf("private_files" to "utf8mb4_bin", "user_media_assets" to "utf8mb4_bin"),
            jdbc.query(
                """
                SELECT table_name, collation_name
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name IN ('private_files', 'user_media_assets')
                  AND column_name = 'storage_key'
                ORDER BY table_name
                """.trimIndent(),
            ) { rs, _ -> rs.getString("table_name") to rs.getString("collation_name") },
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM private_files WHERE storage_key = ?",
                Int::class.java,
                STORAGE_KEY.lowercase(),
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_media_assets WHERE storage_key = ?",
                Int::class.java,
                STORAGE_KEY.lowercase(),
            ),
        )
    }

    private fun migrate(target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    private fun legacyRow(jdbc: JdbcTemplate): PrivateFileMigrationRow = jdbc.queryForObject(
        """
        SELECT id, storage_key, sha256, status, size_bytes
        FROM private_files WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            PrivateFileMigrationRow(
                id = rs.getString("id"),
                storageKey = rs.getString("storage_key"),
                sha256 = rs.getString("sha256"),
                status = rs.getString("status"),
                sizeBytes = rs.getLong("size_bytes"),
                storageProvider = null,
            )
        },
        FILE_ID,
    )!!

    private fun migratedRow(jdbc: JdbcTemplate): PrivateFileMigrationRow = jdbc.queryForObject(
        """
        SELECT id, storage_key, sha256, status, size_bytes, storage_provider
        FROM private_files WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            PrivateFileMigrationRow(
                id = rs.getString("id"),
                storageKey = rs.getString("storage_key"),
                sha256 = rs.getString("sha256"),
                status = rs.getString("status"),
                sizeBytes = rs.getLong("size_bytes"),
                storageProvider = rs.getString("storage_provider"),
            )
        },
        FILE_ID,
    )!!

    private data class PrivateFileMigrationRow(
        val id: String,
        val storageKey: String,
        val sha256: String,
        val status: String,
        val sizeBytes: Long,
        val storageProvider: String?,
    )

    companion object {
        private const val OWNER_ID = "v39-existing-owner"
        private const val FILE_ID = "v39-existing-private-file"
        private const val STORAGE_KEY = "existing/ID_CARD_FRONT/legacy.png"
        private const val SIZE_BYTES = 4_321L
        private const val SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        @Container
        @JvmField
        val mysql = V39UpgradeMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class V39UpgradeMySqlContainer(imageName: String) :
    MySQLContainer<V39UpgradeMySqlContainer>(imageName)
