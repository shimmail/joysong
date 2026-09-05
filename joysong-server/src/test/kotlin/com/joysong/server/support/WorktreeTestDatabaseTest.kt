package com.joysong.server.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorktreeTestDatabaseTest {
    @Test
    fun `database uses exactly one worktree prefix`() {
        assertEquals(
            "myapp_worktree_upload_performance_fix",
            WorktreeTestDatabase.databaseName("worktree_upload_performance_fix"),
        )
    }

    @Test
    fun `database adds the destructive-safe worktree namespace`() {
        assertEquals(
            "myapp_worktree_uat_fast_release",
            WorktreeTestDatabase.databaseName("uat-fast-release"),
        )
    }

    @Test
    fun `database rejects an empty normalized worktree name`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorktreeTestDatabase.databaseName("---")
        }
    }
}
