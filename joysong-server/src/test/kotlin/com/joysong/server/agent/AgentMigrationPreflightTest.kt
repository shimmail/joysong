package com.joysong.server.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

@Tag("mysql-integration")
@Testcontainers
class AgentMigrationPreflightTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `pending V10 allows empty legacy tables and blocks populated legacy data without leaking password`() {
        printIsolationBoundary()
        createPendingV10Schema()
        val emptyResult = runPreflight()

        insertLegacyAgentRow()
        val populatedResult = runPreflight()

        assertEquals(0, emptyResult.exitCode, emptyResult.output)
        assertNotEquals(0, populatedResult.exitCode, populatedResult.output)
        assertTrue(populatedResult.output.contains("V10 migration blocked"), populatedResult.output)
        assertFalse(populatedResult.output.contains(DATABASE_PASSWORD), populatedResult.output)
    }

    private fun createPendingV10Schema() {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE flyway_schema_history (version VARCHAR(50), success BOOLEAN NOT NULL)")
                statement.execute("INSERT INTO flyway_schema_history (version, success) VALUES ('9', TRUE)")
                statement.execute("CREATE TABLE agent_sessions (id VARCHAR(36) PRIMARY KEY)")
                statement.execute("CREATE TABLE agent_messages (id VARCHAR(36) PRIMARY KEY)")
            }
        }
    }

    private fun insertLegacyAgentRow() {
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("INSERT INTO agent_messages (id) VALUES ('legacy-message')")
            }
        }
    }

    private fun runPreflight(): ProcessResult {
        installMysqlCliAdapter()
        val script = Path.of(System.getProperty("user.dir"), "deploy", "preflight-agent-v10.ps1")
        val process = ProcessBuilder(
            "pwsh", "-NoProfile", "-NonInteractive", "-File", script.toString(),
            "-DatabaseHost", mysql.host,
            "-DatabasePort", mysql.getMappedPort(3306).toString(),
            "-DatabaseName", DATABASE_NAME,
            "-DatabaseUser", mysql.username,
            "-TestMode"
        )
            .redirectErrorStream(true)
            .apply {
                environment()["DB_PASSWORD"] = DATABASE_PASSWORD
                environment()["PATH"] = tempDir.toString() + System.getProperty("path.separator") + environment()["PATH"]
                environment()["MYSQL_CONNECTOR_JAR"] = mysqlConnectorJar()
            }
            .start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "preflight process timed out" }
        return ProcessResult(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private fun installMysqlCliAdapter() {
        val source = tempDir.resolve("MysqlCli.java")
        Files.writeString(
            source,
            """
            import java.sql.*;
            public class MysqlCli {
              public static void main(String[] args) throws Exception {
                String host = "", port = "3306", user = "", database = "", sql = "";
                for (int i = 0; i < args.length; i++) {
                  String arg = args[i];
                  if (arg.startsWith("--host=")) host = arg.substring(7);
                  else if (arg.startsWith("--port=")) port = arg.substring(7);
                  else if (arg.startsWith("--user=")) user = arg.substring(7);
                  else if (arg.startsWith("--database=")) database = arg.substring(11);
                  else if (arg.startsWith("--execute=")) {
                    StringBuilder query = new StringBuilder(arg.substring(10));
                    while (++i < args.length) query.append(' ').append(args[i]);
                    sql = query.toString();
                  }
                }
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?allowPublicKeyRetrieval=true&useSSL=false";
                try (Connection connection = DriverManager.getConnection(url, user, System.getenv("MYSQL_PWD"));
                     Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(sql)) {
                  while (rows.next()) System.out.println(rows.getString(1));
                }
              }
            }
            """.trimIndent()
        )
        Files.writeString(
            tempDir.resolve("mysql.ps1"),
            "& '${javaExecutable()}' --class-path \$env:MYSQL_CONNECTOR_JAR '$source' @args\nexit \$LASTEXITCODE\n"
        )
    }

    private fun mysqlConnectorJar(): String =
        System.getProperty("java.class.path").split(System.getProperty("path.separator"))
            .single { it.contains("mysql-connector-j") && it.endsWith(".jar") }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()

    private fun printIsolationBoundary() {
        println("AGENT_PREFLIGHT_TEST_DB_HOST=Testcontainers(${mysql.host}:${mysql.getMappedPort(3306)})")
        println("AGENT_PREFLIGHT_TEST_DB_NAME=$DATABASE_NAME")
        require(DATABASE_NAME.startsWith("myapp_worktree_"))
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    companion object {
        private const val DATABASE_NAME = "myapp_worktree_ai_agent_production_hardening"
        private const val DATABASE_PASSWORD = "preflight-test-secret"

        @Container
        @JvmField
        val mysql = PreflightMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE_NAME)
            .withUsername("preflight_user")
            .withPassword(DATABASE_PASSWORD)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class PreflightMySqlContainer(imageName: String) :
    MySQLContainer<PreflightMySqlContainer>(imageName)
