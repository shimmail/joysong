package com.joysong.server.project.service

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.InstitutionRelationshipReviewAuthorityOperations
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.support.WorktreeTestDatabase
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.aop.framework.ProxyFactory
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.aop.support.AopUtils
import org.springframework.cache.CacheManager
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Tag("mysql-integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfessionalProjectRequestPersistenceTest {
    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactionManager: DataSourceTransactionManager
    private lateinit var transaction: TransactionTemplate
    private lateinit var service: ProfessionalProjectRequestService
    private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @BeforeAll
    fun migrateAndPrepare() {
        val databaseName = WorktreeTestDatabase.databaseName()
        assertTrue(databaseName.startsWith(DESTRUCTIVE_SAFE_PREFIX))
        WorktreeTestDatabase.validateAndPrint(mysql)
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        dataSource = DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password)
        jdbc = JdbcTemplate(dataSource)
        transactionManager = DataSourceTransactionManager(dataSource)
        transaction = TransactionTemplate(transactionManager)
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
    fun `non admin institution review runs through an annotation transaction proxy`() {
        seedInstitutionRequest("proxy", "tx-proxy-doctor")
        jdbc.update(
            "INSERT INTO users (id, password_hash, nickname, role) VALUES ('tx-proxy-legal', 'hash', 'Legal', 'USER')"
        )
        val authorityCalled = AtomicBoolean(false)
        val authority = object : InstitutionRelationshipReviewAuthorityOperations {
            override fun requireCurrentAuthority(actor: ManagementActor, institutionId: String) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
                authorityCalled.set(true)
            }
        }
        val rawService = serviceUsing(jdbc, authority)
        val transactionAdvice = TransactionInterceptor(
            transactionManager as TransactionManager,
            AnnotationTransactionAttributeSource()
        )
        val proxyFactory = ProxyFactory(rawService).apply {
            isProxyTargetClass = true
            addAdvice(transactionAdvice)
        }
        val proxiedService = proxyFactory.proxy as ProfessionalProjectRequestService

        assertTrue(AopUtils.isAopProxy(proxiedService))
        val result = proxiedService.reviewInstitution(
            legalRepresentativeActor("proxy-institution"),
            "proxy-request",
            ProjectRequestReview("REJECTED", "Proxy transaction proof")
        )

        assertTrue(authorityCalled.get())
        assertEquals("REJECTED", result.status)
        assertEquals("REJECTED", requestStatus("proxy-request"))
    }

    @Test
    fun `project caches stay populated until commit clear after commit and survive rollback`() {
        val cacheManager = ConcurrentMapCacheManager("discover", "home", "projects")
        val discover = requireNotNull(cacheManager.getCache("discover"))
        val home = requireNotNull(cacheManager.getCache("home"))
        val projects = requireNotNull(cacheManager.getCache("projects"))
        discover.put("proof", "discover-before-commit")
        home.put("proof", "home-before-commit")
        projects.put("proof", "projects-before-commit")
        seedInstitutionRequest("cache-success", "tx-cache-success-doctor")
        val cacheService = serviceUsing(jdbc, cacheManager = cacheManager)

        inTransaction {
            cacheService.reviewInstitution(
                adminActor(),
                "cache-success-request",
                ProjectRequestReview("APPROVED")
            )
            assertEquals("discover-before-commit", discover.get("proof")?.get())
            assertEquals("home-before-commit", home.get("proof")?.get())
            assertEquals("projects-before-commit", projects.get("proof")?.get())
        }

        assertEquals(null, discover.get("proof"))
        assertEquals(null, home.get("proof"))
        assertEquals(null, projects.get("proof"))

        discover.put("proof", "discover-before-rollback")
        home.put("proof", "home-before-rollback")
        projects.put("proof", "projects-before-rollback")
        seedInstitutionRequest("cache-rollback", "tx-cache-rollback-doctor")

        val rollback = assertThrows<IllegalStateException> {
            inTransaction {
                val result = cacheService.reviewInstitution(
                    adminActor(),
                    "cache-rollback-request",
                    ProjectRequestReview("APPROVED")
                )
                assertEquals("APPROVED", result.status)
                assertEquals("APPROVED", requestStatus("cache-rollback-request"))
                assertEquals(1, count("institution_projects", "institution_id", "cache-rollback-institution"))
                assertEquals(1, count("doctor_projects", "doctor_id", "tx-cache-rollback-doctor"))
                assertEquals(1, count("doctor_institution_project_configs", "doctor_id", "tx-cache-rollback-doctor"))
                assertEquals("discover-before-rollback", discover.get("proof")?.get())
                assertEquals("home-before-rollback", home.get("proof")?.get())
                assertEquals("projects-before-rollback", projects.get("proof")?.get())

                error("force outer rollback after cache synchronization registration")
            }
        }

        assertEquals("force outer rollback after cache synchronization registration", rollback.message)
        assertEquals("discover-before-rollback", discover.get("proof")?.get())
        assertEquals("home-before-rollback", home.get("proof")?.get())
        assertEquals("projects-before-rollback", projects.get("proof")?.get())
        assertEquals("PENDING", requestStatus("cache-rollback-request"))
        assertEquals(0, count("institution_projects", "institution_id", "cache-rollback-institution"))
        assertEquals(0, count("doctor_projects", "doctor_id", "tx-cache-rollback-doctor"))
        assertEquals(0, count("doctor_institution_project_configs", "doctor_id", "tx-cache-rollback-doctor"))
    }

    @Test
    fun `platform and institution approvals persist exact target list column boundaries`() {
        val tagsAtLimit = List(5) { index -> "t".repeat(if (index == 4) 96 else 100) }
        val categoryTagsAtLimit = List(5) { index -> "c".repeat(if (index == 4) 96 else 100) }
        val imagesAtLimit = List(4) { index -> "i".repeat(if (index == 3) 497 else 500) }
        assertEquals(500, tagsAtLimit.joinToString(",").length)
        assertEquals(500, categoryTagsAtLimit.joinToString(",").length)
        assertEquals(2_000, imagesAtLimit.joinToString(",").length)
        seedPlatformRequest(
            "boundary-platform",
            "tx-boundary-platform-doctor",
            tagsAtLimit,
            categoryTagsAtLimit,
            imagesAtLimit
        )
        seedInstitutionRequest(
            "boundary-institution",
            "tx-boundary-institution-doctor",
            tagsAtLimit,
            imagesAtLimit
        )

        val platformResult = inTransaction {
            service.reviewPlatform(adminActor(), "boundary-platform-request", ProjectRequestReview("APPROVED"))
        }
        val institutionResult = inTransaction {
            service.reviewInstitution(adminActor(), "boundary-institution-request", ProjectRequestReview("APPROVED"))
        }

        assertEquals(
            listOf(500, 500, 2_000),
            jdbc.queryForObject(
                "SELECT CHAR_LENGTH(tags), CHAR_LENGTH(category_tags), CHAR_LENGTH(images) FROM projects WHERE id = ?",
                { rs, _ -> listOf(rs.getInt(1), rs.getInt(2), rs.getInt(3)) },
                platformResult.resultingProjectId
            )
        )
        assertEquals(
            listOf(500, 2_000),
            jdbc.queryForObject(
                "SELECT CHAR_LENGTH(tags), CHAR_LENGTH(images) FROM institution_projects WHERE id = ?",
                { rs, _ -> listOf(rs.getInt(1), rs.getInt(2)) },
                institutionResult.resultingInstitutionProjectId
            )
        )
        assertEquals(
            listOf(500, 2_000),
            jdbc.queryForObject(
                "SELECT CHAR_LENGTH(service_tags), CHAR_LENGTH(images) FROM doctor_projects WHERE institution_project_id = ?",
                { rs, _ -> listOf(rs.getInt(1), rs.getInt(2)) },
                institutionResult.resultingInstitutionProjectId
            )
        )
    }

    @Test
    fun `legacy oversized target lists fail validation before approval writes`() {
        val oversizedTags = List(5) { "t".repeat(100) }
        val oversizedImages = List(4) { "i".repeat(500) }
        assertEquals(504, oversizedTags.joinToString(",").length)
        assertEquals(2_003, oversizedImages.joinToString(",").length)
        seedPlatformRequest(
            "oversized-platform",
            "tx-oversized-platform-doctor",
            oversizedTags,
            emptyList(),
            emptyList()
        )
        seedInstitutionRequest(
            "oversized-institution",
            "tx-oversized-institution-doctor",
            emptyList(),
            oversizedImages
        )

        val platformError = assertThrows<IllegalArgumentException> {
            inTransaction {
                service.reviewPlatform(adminActor(), "oversized-platform-request", ProjectRequestReview("APPROVED"))
            }
        }
        val institutionError = assertThrows<IllegalArgumentException> {
            inTransaction {
                service.reviewInstitution(adminActor(), "oversized-institution-request", ProjectRequestReview("APPROVED"))
            }
        }

        assertEquals("项目标签不能超过 500 个字符", platformError.message)
        assertEquals("项目图片不能超过 2000 个字符", institutionError.message)
        assertEquals("PENDING", requestStatus("oversized-platform-request"))
        assertEquals("PENDING", requestStatus("oversized-institution-request"))
        assertEquals(0, count("institution_projects", "institution_id", "oversized-institution-institution"))
        assertEquals(0, count("doctor_projects", "doctor_id", "tx-oversized-institution-doctor"))
        assertEquals(0, count("doctor_institution_project_configs", "doctor_id", "tx-oversized-institution-doctor"))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM projects WHERE name = 'oversized-platform Project'",
                Int::class.java
            )
        )
    }

    @Test
    fun `two concurrent reviews produce one success one conflict and no duplicate targets`() {
        seedInstitutionRequest("concurrent", "tx-concurrent-doctor")
        val start = CountDownLatch(1)
        val transactionsReady = CyclicBarrier(2)
        val lockAttemptsReady = CyclicBarrier(2)
        val lockAttempts = AtomicInteger(0)
        val coordinatingService = serviceUsing(
            LockCoordinatingJdbcTemplate(dataSource, lockAttemptsReady, lockAttempts)
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<Throwable?> {
                    start.await()
                    try {
                        inTransaction {
                            transactionsReady.await(10, TimeUnit.SECONDS)
                            coordinatingService.reviewInstitution(
                                adminActor(),
                                "concurrent-request",
                                ProjectRequestReview("APPROVED")
                            )
                        }
                        null
                    } catch (error: Throwable) {
                        error
                    }
                }
            }
            start.countDown()
            val outcomes = attempts.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(2, lockAttempts.get())
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

    private fun seedPlatformRequest(
        prefix: String,
        doctorId: String,
        tags: List<String>,
        categoryTags: List<String>,
        images: List<String>
    ) {
        jdbc.update(
            "INSERT INTO users (id, password_hash, nickname, role) VALUES (?, 'hash', ?, 'DOCTOR')",
            doctorId,
            "$prefix Doctor"
        )
        jdbc.update("INSERT INTO doctors (id, name) VALUES (?, ?)", doctorId, "$prefix Doctor")
        jdbc.update(
            """
            INSERT INTO professional_project_requests
                (id, request_type, doctor_id, name, category, description, tags, slogan,
                 detail_content, currency, cover_image, images, sales_count, reference_price,
                 category_tags, notes)
            VALUES (?, 'PLATFORM', ?, ?, 'BOUNDARY', 'boundary description', ?, '',
                    NULL, 'USD', '', ?, 0, 99.00, ?, 'approval fixture')
            """.trimIndent(),
            "$prefix-request",
            doctorId,
            "$prefix Project",
            objectMapper.writeValueAsString(tags),
            objectMapper.writeValueAsString(images),
            objectMapper.writeValueAsString(categoryTags)
        )
    }

    private fun seedInstitutionRequest(
        prefix: String,
        doctorId: String,
        requestTags: List<String>? = emptyList(),
        requestImages: List<String>? = emptyList()
    ) {
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
                    ?, '', NULL, 'USD', '', ?, 3,
                    NULL, NULL, 99.00, 120.00, 1, 10.00, 20.00, 30.00, 'approval fixture')
            """.trimIndent(),
            "$prefix-request",
            doctorId,
            "$prefix-institution",
            "$prefix-project",
            requestTags?.let(objectMapper::writeValueAsString),
            requestImages?.let(objectMapper::writeValueAsString)
        )
    }

    private fun <T> inTransaction(action: () -> T): T = requireNotNull(transaction.execute { action() })

    private fun serviceUsing(
        template: JdbcTemplate,
        authority: InstitutionRelationshipReviewAuthorityOperations = adminOnlyAuthority,
        cacheManager: CacheManager = ConcurrentMapCacheManager("discover", "home")
    ) = ProfessionalProjectRequestService(
        template,
        objectMapper,
        OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }),
        authority,
        cacheManager,
        mockk<BusinessNotificationService>(relaxed = true)
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

    private fun legalRepresentativeActor(institutionId: String) = ManagementActor(
        "tx-proxy-legal",
        false,
        setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        null,
        setOf(institutionId),
        emptySet(),
        emptySet()
    )

    private val adminOnlyAuthority = object : InstitutionRelationshipReviewAuthorityOperations {
        override fun requireCurrentAuthority(actor: ManagementActor, institutionId: String) {
            error("Admin persistence scenarios must not call institution authority")
        }
    }

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

private class LockCoordinatingJdbcTemplate(
    dataSource: DriverManagerDataSource,
    private val lockAttemptsReady: CyclicBarrier,
    private val lockAttempts: AtomicInteger
) : JdbcTemplate(dataSource) {
    override fun <T : Any?> query(
        sql: String,
        rowMapper: RowMapper<T>,
        vararg args: Any?
    ): MutableList<T> {
        if (sql.contains("FROM professional_project_requests") && sql.contains("FOR UPDATE")) {
            lockAttempts.incrementAndGet()
            lockAttemptsReady.await(10, TimeUnit.SECONDS)
        }
        return super.query(sql, rowMapper, *args)
    }
}
