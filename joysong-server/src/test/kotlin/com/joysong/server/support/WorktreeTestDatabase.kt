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
        val worktreeId = worktree.fileName.toString()
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
        return "myapp_worktree_$worktreeId"
    }

    fun validateAndPrint(container: MySQLContainer<*>) {
        val databaseName = container.databaseName
        require(databaseName == databaseName()) {
            "Migration database must be derived from the current worktree: $databaseName"
        }
        require(databaseName.startsWith("myapp_worktree_"))
        println("Migration database host=${container.host}:${container.getMappedPort(3306)}, database=$databaseName")
    }

    private fun currentDirectory(): Path =
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
}
