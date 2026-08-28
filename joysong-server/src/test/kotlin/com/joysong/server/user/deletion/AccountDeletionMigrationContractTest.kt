package com.joysong.server.user.deletion

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class AccountDeletionMigrationContractTest {
    private val migration = Files.readString(
        Path.of("src/main/resources/db/migration/V36__add_account_lifecycle_foundation.sql"),
    )

    @Test
    fun `V36 contains the complete hashed workflow state`() {
        listOf(
            "account_state",
            "erased_at",
            "erased_phone_digest",
            "erased_email_digest",
            "account_deletion_requests",
            "policy_version",
            "verification_code_hash",
            "verification_attempt_count",
            "verification_expires_at",
            "resend_available_at",
            "authorization_hash",
            "authorization_expires_at",
            "authorization_consumed_at",
            "idempotency_key_hash",
            "terminal_outcome",
            "terminal_result_json",
            "user_media_assets",
            "storage_key",
            "storage_provider",
        ).forEach { column -> assertTrue(migration.contains(column), "missing $column") }
    }

    @Test
    fun `V36 neither migrates deleted at nor changes commerce structures`() {
        assertFalse(migration.contains("deleted_at", ignoreCase = true))
        listOf("orders", "payments", "refunds", "wallets", "settlements", "reviews", "dm_messages")
            .forEach { table -> assertFalse(migration.contains("`$table`", ignoreCase = true), "touched $table") }
    }
}
