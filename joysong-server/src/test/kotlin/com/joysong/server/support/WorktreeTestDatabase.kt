package com.joysong.server.support

import org.testcontainers.containers.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object WorktreeTestDatabase {
    fun databaseName(): String {
        val worktree = generateSequence(currentDirectory()) { it.parent }
            .firstOrNull { Files.exists(it.resolve(".git")) }
            ?: error("Unable to find the current Git worktree from ${currentDirectory()}")
        return databaseName(worktree.fileName.toString())
    }

    internal fun databaseName(worktreeDirectoryName: String): String {
        val normalized = worktreeDirectoryName
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
        require(normalized.isNotBlank()) { "Worktree directory name cannot be empty" }
        val normalizedId = normalized.removePrefix("worktree_")
        val worktreeId = "worktree_$normalizedId"
        val databaseName = "myapp_$worktreeId"
        require(databaseName.startsWith("myapp_worktree_")) {
            "Migration tests require an isolated worktree database: $worktreeDirectoryName"
        }
        return databaseName
    }

    fun validateAndPrint(container: MySQLContainer<*>) {
        val databaseName = container.databaseName
        require(databaseName == databaseName()) {
            "Migration database must be derived from the current worktree: $databaseName"
        }
        require(databaseName.startsWith("myapp_worktree_")) {
            "Refusing to use a migration database outside the isolated worktree namespace"
        }
        println("Migration database host=${container.host}:${container.getMappedPort(3306)}, database=$databaseName")
    }

    private fun currentDirectory(): Path =
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
}
