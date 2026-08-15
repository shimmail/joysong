package com.joysong.server.project.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityOperations
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("mysql-integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfessionalProjectRequestPersistenceTest {
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transaction: TransactionTemplate
    private lateinit var service: ProfessionalProjectRequestService

    @BeforeAll
    fun migrateAndPrepare() {
        val databaseName = WorktreeTestDatabase.databaseName()
        assertEquals(EXPECTED_DATABASE, databaseName)
        assertTrue(databaseName.startsWith(DESTRUCTIVE_SAFE_PREFIX))
        WorktreeTestDatabase.validateAndPrint(mysql)
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
        jdbc = JdbcTemplate(dataSource)
        transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))
        service = serviceUsing(jdbc)
        jdbc.update("INSERT INTO users (id, password_hash, nickname, role) VALUES ('tx-admin', 'hash', 'Admin', 'ADMIN')")
    }

    @Test
    fun `successful approval atomically creates inherited target applicant binding config and closes request`() {
        seedInstitutionRequest("success", "tx-success-doctor")

        val result = inTransaction {
            service.reviewInstitution(adminActor(), "success-request", ProjectRequestReview("APPROVED"))
        }

        assertEquals("APPROVED", result.status)
        assertEquals(
            InstitutionProjectSnapshot(
                name = "success Base Project",
                category = "BASE",
                description = "success base description",
                rating = BigDecimal("0.0"),
                reviewCount = 0,
                tags = "base-one,base-two",
                slogan = "success base slogan",
                detailContent = "success base details",
                price = BigDecimal("99.00"),
                originalPrice = BigDecimal("120.00"),
                currency = "USD",
                coverImage = "success-base-cover.png",
                images = "success-base-one.png,success-base-two.png",
                salesCount = 3,
                active = true
            ),
            jdbc.queryForObject(
                """
                SELECT name, category, description, rating, review_count, tags, slogan, detail_content,
                       price, original_price, currency, cover_image, images, sales_count, is_active
                FROM institution_projects WHERE id = ?
                """.trimIndent(),
                { rs, _ ->
                    InstitutionProjectSnapshot(
                        name = rs.getString("name"),
                        category = rs.getString("category"),
                        description = rs.getString("description"),
                        rating = rs.getBigDecimal("rating"),
                        reviewCount = rs.getInt("review_count"),
                        tags = rs.getString("tags"),
                        slogan = rs.getString("slogan"),
                        detailContent = rs.getString("detail_content"),
                        price = rs.getBigDecimal("price"),
                        originalPrice = rs.getBigDecimal("original_price"),
                        currency = rs.getString("currency"),
                        coverImage = rs.getString("cover_image"),
                        images = rs.getString("images"),
                        salesCount = rs.getInt("sales_count"),
                        active = rs.getBoolean("is_active")
                    )
                },
                result.resultingInstitutionProjectId
            )
        )
        assertEquals(
            DoctorBindingSnapshot(
                doctorId = "tx-success-doctor",
                description = "success base description",
                tags = "base-one,base-two",
                scheduleNote = "",
                coverImage = "success-base-cover.png",
                images = "success-base-one.png,success-base-two.png",
                price = BigDecimal("99.00")
            ),
            jdbc.queryForObject(
                """
                SELECT doctor_id, service_description, service_tags, schedule_note, cover_image, images, price
                FROM doctor_projects WHERE institution_project_id = ?
                """.trimIndent(),
                { rs, _ ->
                    DoctorBindingSnapshot(
                        doctorId = rs.getString("doctor_id"),
                        description = rs.getString("service_description"),
                        tags = rs.getString("service_tags"),
                        scheduleNote = rs.getString("schedule_note"),
                        coverImage = rs.getString("cover_image"),
                        images = rs.getString("images"),
                        price = rs.getBigDecimal("price")
                    )
                },
                result.resultingInstitutionProjectId
            )
        )
        assertEquals(
            listOf(BigDecimal("10.00"), BigDecimal("20.00"), BigDecimal("30.00")),
            jdbc.queryForObject(
                """
                SELECT consultation_fee, commission_rate, institution_rate
                FROM doctor_institution_project_configs WHERE institution_project_id = ?
                """.trimIndent(),
                { rs, _ -> listOf(
                    rs.getBigDecimal("consultation_fee"),
                    rs.getBigDecimal("commission_rate"),
                    rs.getBigDecimal("institution_rate")
                ) },
                result.resultingInstitutionProjectId
            )
        )
        assertEquals("APPROVED", requestStatus("success-request"))
    }

    @Test
    fun `forced final config failure rolls back all target writes and leaves request pending`() {
        seedInstitutionRequest("failure", "tx-failure-doctor")
        val failingService = serviceUsing(FailingConfigJdbcTemplate(dataSource))

        val error = assertThrows<DataAccessException> {
            inTransaction {
                failingService.reviewInstitution(adminActor(), "failure-request", ProjectRequestReview("APPROVED"))
            }
        }

        assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it.message?.contains("forced config failure") == true })
        assertEquals(0, count("institution_projects", "institution_id", "failure-institution"))
        assertEquals(0, count("doctor_projects", "doctor_id", "tx-failure-doctor"))
        assertEquals(0, count("doctor_institution_project_configs", "doctor_id", "tx-failure-doctor"))
        assertEquals("PENDING", requestStatus("failure-request"))
    }

    @Test
    fun `two concurrent reviews produce one success one conflict and no duplicate targets`() {
        seedInstitutionRequest("concurrent", "tx-concurrent-doctor")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<Throwable?> {
                    start.await()
                    try {
                        inTransaction {
                            service.reviewInstitution(adminActor(), "concurrent-request", ProjectRequestReview("APPROVED"))
                        }
                        null
                    } catch (error: Throwable) {
                        error
                    }
                }
            }
            start.countDown()
            val outcomes = attempts.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it == null })
            val conflict = outcomes.filterNotNull().single()
            assertInstanceOf(ProfessionalProjectRequestConflictException::class.java, conflict)
            assertEquals("项目申请已处理", conflict.message)
        } finally {
            executor.shutdownNow()
        }

        assertEquals("APPROVED", requestStatus("concurrent-request"))
        assertEquals(1, count("institution_projects", "institution_id", "concurrent-institution"))
        assertEquals(1, count("doctor_projects", "doctor_id", "tx-concurrent-doctor"))
        assertEquals(1, count("doctor_institution_project_configs", "doctor_id", "tx-concurrent-doctor"))
    }

    private fun seedInstitutionRequest(prefix: String, doctorId: String) {
        jdbc.update(
            "INSERT INTO users (id, password_hash, nickname, role) VALUES (?, 'hash', ?, 'DOCTOR')",
            doctorId,
            "$prefix Doctor"
        )
        jdbc.update("INSERT INTO doctors (id, name) VALUES (?, ?)", doctorId, "$prefix Doctor")
        jdbc.update("INSERT INTO institutions (id, name) VALUES (?, ?)", "$prefix-institution", "$prefix Institution")
        jdbc.update(
            """
            INSERT INTO projects
                (id, name, category, description, tags, category_tags, cover_image, images,
                 reference_price, currency, slogan, detail_content, rating, review_count, sales_count)
            VALUES (?, ?, 'BASE', ?, 'base-one,base-two', 'base-category', ?, ?,
                    199.00, 'CNY', ?, ?, 4.8, 25, 5)
            """.trimIndent(),
            "$prefix-project",
            "$prefix Base Project",
            "$prefix base description",
            "$prefix-base-cover.png",
            "$prefix-base-one.png,$prefix-base-two.png",
            "$prefix base slogan",
            "$prefix base details"
        )
        jdbc.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, status, revoked_at, deleted_at)
            VALUES (?, ?, ?, 'APPROVED', NULL, NULL)
            """.trimIndent(),
            "$prefix-relationship",
            doctorId,
            "$prefix-institution"
        )
        jdbc.update(
            """
            INSERT INTO professional_project_requests
                (id, request_type, doctor_id, institution_id, project_id, name, category, description,
                 tags, slogan, detail_content, currency, cover_image, images, sales_count,
                 reference_price, category_tags, price, original_price, is_active,
                 consultation_fee, commission_rate, institution_rate, notes)
            VALUES (?, 'INSTITUTION', ?, ?, ?, NULL, NULL, NULL,
                    NULL, '', NULL, 'USD', '', NULL, 3,
                    NULL, NULL, 99.00, 120.00, 1, 10.00, 20.00, 30.00, 'approval fixture')
            """.trimIndent(),
            "$prefix-request",
            doctorId,
            "$prefix-institution",
            "$prefix-project"
        )
    }

    private fun <T> inTransaction(action: () -> T): T = requireNotNull(transaction.execute { action() })

    private fun serviceUsing(template: JdbcTemplate) = ProfessionalProjectRequestService(
        template,
        jacksonObjectMapper().registerModule(JavaTimeModule()),
        OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }),
        object : InstitutionRelationshipReviewAuthorityOperations {
            override fun requireCurrentAuthority(actor: ManagementActor, institutionId: String) {
                error("Admin persistence scenarios must not call institution authority")
            }
        },
        ConcurrentMapCacheManager("discover", "home")
    )

    private fun requestStatus(id: String): String = jdbc.queryForObject(
        "SELECT status FROM professional_project_requests WHERE id = ?",
        String::class.java,
        id
    )

    private fun count(table: String, column: String, value: String): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM $table WHERE $column = ?",
        Int::class.java,
        value
    )

    private fun adminActor() = ManagementActor(
        "tx-admin", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet()
    )

    private data class InstitutionProjectSnapshot(
        val name: String,
        val category: String,
        val description: String,
        val rating: BigDecimal,
        val reviewCount: Int,
        val tags: String,
        val slogan: String,
        val detailContent: String,
        val price: BigDecimal,
        val originalPrice: BigDecimal,
        val currency: String,
        val coverImage: String,
        val images: String,
        val salesCount: Int,
        val active: Boolean
    )

    private data class DoctorBindingSnapshot(
        val doctorId: String,
        val description: String,
        val tags: String,
        val scheduleNote: String,
        val coverImage: String,
        val images: String,
        val price: BigDecimal
    )

    companion object {
        private const val EXPECTED_DATABASE = "myapp_worktree_doctor_project_application_full_vo"
        private const val DESTRUCTIVE_SAFE_PREFIX = "myapp_worktree_"

        @Container
        @JvmField
        val mysql = ProfessionalProjectRequestMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

private class FailingConfigJdbcTemplate(dataSource: DriverManagerDataSource) : JdbcTemplate(dataSource) {
    override fun update(sql: String, vararg args: Any?): Int {
        if (sql.contains("INSERT INTO doctor_institution_project_configs")) {
            throw DataIntegrityViolationException("forced config failure")
        }
        return super.update(sql, *args)
    }
}
