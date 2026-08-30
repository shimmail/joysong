package com.joysong.server.user.deletion

import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

class AccountDeletionDataEraserTest {
    @Test
    fun `account erasure retains private files bound to refund evidence`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val properties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        val eraser = JdbcAccountDeletionDataEraser(
            jdbcTemplate,
            AccountDeletionCrypto(properties),
            UserMediaAssetService(jdbcTemplate, mockk<AccountLifecycleGuard>(relaxed = true)),
        )

        eraser.erase(
            UserEntity(id = "user-1", phone = "+8613800138000", passwordHash = "hash"),
            LocalDateTime.parse("2026-08-30T10:00:00"),
        )

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE user_media_assets uma") &&
                        it.contains("JOIN private_files pf ON pf.storage_key") &&
                        it.contains("ref.file_id = pf.id")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("DELETE FROM private_files") &&
                        it.contains("ref.file_id = private_files.id")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("WHERE uma.owner_user_id = ?") &&
                        it.contains("FROM private_files pf") &&
                        it.contains("ref.file_id = pf.id")
                },
                "user-1",
            )
        }
    }

    @Test
    fun `erasure removes private data from revoked professional identity history`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val properties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        val eraser = JdbcAccountDeletionDataEraser(
            jdbcTemplate = jdbcTemplate,
            crypto = AccountDeletionCrypto(properties),
            userMediaAssetService = UserMediaAssetService(
                jdbcTemplate,
                mockk<AccountLifecycleGuard>(relaxed = true),
            ),
        )

        eraser.erase(
            UserEntity(id = "user-1", phone = "+8613800138000", passwordHash = "hash"),
            LocalDateTime.parse("2026-08-29T10:00:00"),
        )

        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("DELETE FROM identity_application_documents") &&
                        it.contains("identity_applications")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE identity_applications") &&
                        it.contains("application_data = JSON_OBJECT()") &&
                        it.contains("review_note = ''")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("DELETE FROM doctor_institutions") &&
                        it.contains("status <> 'APPROVED'")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("DELETE FROM platform_cooperation_agreements") &&
                        it.contains("status = 'TERMINATED'")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("DELETE FROM private_files") &&
                        it.contains("platform_cooperation_agreements")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE user_media_assets") &&
                        it.contains("WHERE uma.owner_user_id = ?") &&
                        it.contains("platform_cooperation_agreements")
                },
                "user-1",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE doctors") &&
                        it.contains("name = ''") &&
                        it.contains("credentials = ''") &&
                        it.contains("is_verified = 0") &&
                        it.contains("deleted_at = COALESCE(deleted_at, NOW())")
                },
                "user-1",
            )
        }
    }

    @Test
    fun `erasure recomputes counters for every diary and comment affected by removed user content`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT DISTINCT diary_id") && it.contains("FROM comments") },
                String::class.java,
                "user-1",
            )
        } returns listOf("diary-from-comment")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT DISTINCT target_id") && it.contains("FROM likes") && it.contains("target_type = 'diary'") },
                String::class.java,
                "user-1",
            )
        } returns listOf("diary-from-like")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT DISTINCT target_id") && it.contains("FROM favorites") && it.contains("target_type = 'DIARY'") },
                String::class.java,
                "user-1",
            )
        } returns listOf("diary-from-favorite")
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT DISTINCT target_id") && it.contains("FROM likes") && it.contains("target_type = 'comment'") },
                String::class.java,
                "user-1",
            )
        } returns listOf("comment-from-like")

        val properties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        val eraser = JdbcAccountDeletionDataEraser(
            jdbcTemplate = jdbcTemplate,
            crypto = AccountDeletionCrypto(properties),
            userMediaAssetService = mockk(relaxed = true),
        )

        eraser.erase(
            UserEntity(id = "user-1", phone = "+8613800138000", passwordHash = "hash"),
            LocalDateTime.parse("2026-08-28T10:00:00"),
        )

        verifyOrder {
            jdbcTemplate.queryForList(
                match<String> { it.contains("SELECT DISTINCT diary_id") && it.contains("FROM comments") },
                String::class.java,
                "user-1",
            )
            jdbcTemplate.update(match<String> { it.contains("DELETE FROM comments WHERE user_id = ?") }, "user-1")
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE diaries") &&
                        it.contains("comment_count = (") &&
                        it.contains("SELECT COUNT(*) FROM comments") &&
                        it.contains("like_count = (") &&
                        it.contains("SELECT COUNT(*) FROM likes") &&
                        it.contains("favorite_count = (") &&
                        it.contains("SELECT COUNT(*) FROM favorites") &&
                        it.contains("WHERE d.id IN (?, ?, ?)")
                },
                "diary-from-comment",
                "diary-from-like",
                "diary-from-favorite",
            )
        }
        verify(exactly = 1) {
            jdbcTemplate.update(
                match<String> {
                    it.contains("UPDATE comments") &&
                        it.contains("like_count = (") &&
                        it.contains("SELECT COUNT(*) FROM likes") &&
                        it.contains("WHERE c.id IN (?)")
                },
                "comment-from-like",
            )
        }
    }
}
