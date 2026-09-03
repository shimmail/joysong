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
    fun `database rejects a directory outside the destructive-safe namespace`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorktreeTestDatabase.databaseName("upload_performance_fix")
        }
    }
}
