package com.joysong.server.user.deletion

import com.joysong.server.user.entity.UserEntity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

class AccountDeletionDataEraserTest {
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
