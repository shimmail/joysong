package com.joysong.server.demo

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

@Tag("mysql-integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoCatalogSnapshotMySqlIntegrationTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val passwordEncoder = BCryptPasswordEncoder(4)
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: DemoCatalogService
    private lateinit var loaded: LoadedDemoCatalog
    private lateinit var privateUploadDirectory: Path

    @BeforeAll
    fun migrateAndPrepareAdminBaseline() {
        WorktreeTestDatabase.validateAndPrint(mysql)
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
        jdbc = JdbcTemplate(dataSource)
        jdbc.update(
            """
            INSERT INTO users (id, phone, password_hash, nickname, role, account_state)
            VALUES (?, ?, ?, ?, 'ADMIN', 'ACTIVE')
            """.trimIndent(),
            ADMIN_ID,
            "13800000000",
            passwordEncoder.encode(ADMIN_PASSWORD),
            "Demo Test Admin",
        )

        privateUploadDirectory = Files.createDirectories(
            Path.of(System.getProperty("user.dir"), ".runtime", "demo-private-${UUID.randomUUID()}")
                .toAbsolutePath()
                .normalize(),
        )
        loaded = DemoCatalogLoader(objectMapper).load(catalogPath())
        service = transactionalService(dataSource)
    }

    @AfterAll
    fun cleanPrivateFiles() {
        deleteTree(privateUploadDirectory)
    }

    @Test
    fun `apply rolls back on a late database failure then verifies and replays without writes`() {
        jdbc.execute(
            """
            CREATE TRIGGER fail_demo_catalog_request_insert
            BEFORE INSERT ON professional_project_requests
            FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced demo catalog rollback'
            """.trimIndent(),
        )
        try {
            assertThrows<UncategorizedSQLException> {
                service.apply(loaded, DEMO_ACCOUNT_PASSWORD)
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS fail_demo_catalog_request_insert")
        }

        assertEquals(1, count("users"))
        ROLLED_BACK_TABLES.forEach { table -> assertEquals(0, count(table), table) }
        assertEquals(0, regularFileCount(privateUploadDirectory))

        val firstApply = service.apply(loaded, DEMO_ACCOUNT_PASSWORD)
        assertEquals(378, firstApply.createdRows)
        assertEquals(378, firstApply.managedRows)

        val verify = service.verify(loaded, DEMO_ACCOUNT_PASSWORD)
        assertEquals(0, verify.createdRows)
        assertEquals(378, verify.managedRows)

        val secondApply = service.apply(loaded, DEMO_ACCOUNT_PASSWORD)
        assertEquals(0, secondApply.createdRows)
        assertEquals(378, secondApply.managedRows)

        assertExactCounts()
        assertEquals(80, regularFileCount(privateUploadDirectory))
    }

    private fun transactionalService(dataSource: DriverManagerDataSource): DemoCatalogService {
        val rawService = DemoCatalogService(
            jdbc,
            objectMapper,
            passwordEncoder,
            privateUploadDirectory.toString(),
        )
        val transactionAdvice = TransactionInterceptor(
            DataSourceTransactionManager(dataSource) as TransactionManager,
            AnnotationTransactionAttributeSource(),
        )
        return ProxyFactory(rawService).apply {
            isProxyTargetClass = true
            addAdvice(transactionAdvice)
        }.proxy.let { proxy ->
            assertTrue(AopProxyUtils.ultimateTargetClass(proxy) == DemoCatalogService::class.java)
            proxy as DemoCatalogService
        }
    }

    private fun assertExactCounts() {
        val expected = linkedMapOf(
            "users" to 21,
            "private_files" to 80,
            "identity_applications" to 20,
            "identity_application_documents" to 80,
            "user_roles" to 20,
            "institutions" to 6,
            "institution_memberships" to 12,
            "doctors" to 10,
            "doctor_institutions" to 10,
            "wallets" to 22,
            "projects" to 12,
            "institution_projects" to 21,
            "doctor_projects" to 30,
            "doctor_institution_project_configs" to 30,
            "professional_project_requests" to 5,
            "orders" to 0,
            "payments" to 0,
            "reviews" to 0,
        )
        expected.forEach { (table, count) -> assertEquals(count, count(table), table) }
    }

    private fun count(table: String): Int {
        check(table in COUNTED_TABLES) { "Unexpected table: $table" }
        return requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java))
    }

    private fun regularFileCount(root: Path): Long {
        if (!Files.exists(root)) return 0
        return Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).count() }
    }

    private fun catalogPath(): Path = repositoryRoot().resolve("docs/test/catalog-v1.json")

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) }
        ?: error("Unable to locate repository root")

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private const val ADMIN_ID = "demo-test-admin"
        private const val ADMIN_PASSWORD = "DemoAdminPass!2026"
        private const val DEMO_ACCOUNT_PASSWORD = "DemoAccountPass!2026"
        private val ROLLED_BACK_TABLES = listOf(
            "private_files", "identity_applications", "identity_application_documents", "user_roles",
            "institutions", "institution_memberships", "doctors", "doctor_institutions", "wallets",
            "projects", "institution_projects", "doctor_projects", "doctor_institution_project_configs",
            "professional_project_requests",
        )
        private val COUNTED_TABLES = ROLLED_BACK_TABLES.toSet() +
            setOf("users", "orders", "payments", "reviews")

        @Container
        @JvmField
        val mysql = DemoCatalogMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
            .withCommand("--log-bin-trust-function-creators=1")
    }
}

class DemoCatalogMySqlContainer(imageName: String) :
    MySQLContainer<DemoCatalogMySqlContainer>(imageName)
