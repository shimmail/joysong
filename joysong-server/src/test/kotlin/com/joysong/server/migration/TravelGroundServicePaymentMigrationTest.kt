package com.joysong.server.migration

import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.dm.service.OrderServiceConversationService
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.order.consultant.ConsultantOrderAccessPolicy
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.entity.PaymentEventEntity
import com.joysong.server.payment.repository.PaymentEventRepository
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.support.LegacyMigrationTestResources
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    OrderServiceConversationService::class,
    ConsultantOrderAccessPolicy::class,
    IdentityAuthorizationService::class
)
class TravelGroundServicePaymentMigrationTest {

    @TempDir
    lateinit var legacyMigrationDirectory: Path

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var conversationRepository: DmConversationRepository

    @Autowired
    private lateinit var orderServiceConversationService: OrderServiceConversationService

    @Autowired
    private lateinit var paymentEventRepository: PaymentEventRepository

    @Autowired
    private lateinit var paymentRepository: PaymentRepository

    @Autowired
    private lateinit var refundItemRepository: RefundItemRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `fresh database applies the travel payment schema constraints and index set`() {
        assertEquals(DATABASE, freshMysql.databaseName)
        assertEquals(
            listOf("33", "34", "35"),
            jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String::class.java
            )
        )

        assertColumn("doctor_institution_project_configs", "medical_list_price", "decimal", false)
        assertDecimalScale("doctor_institution_project_configs", "medical_list_price", 2)
        assertColumn("doctor_project_change_requests", "medical_list_price", "decimal", true)
        assertColumn("doctor_project_change_requests", "current_medical_list_price", "decimal", true)
        assertColumn("orders", "payment_flow", "varchar", false)
        assertColumn("orders", "medical_list_price_minor", "bigint", true)
        assertColumn("orders", "platform_service_rate_bps", "int", true)
        assertColumn("orders", "pricing_policy_revision", "varchar", true)
        assertColumn("orders", "travel_ground_service_fee_minor", "bigint", true)
        assertColumn("orders", "consultant_avatar", "varchar", true)
        assertColumn("orders", "service_activated_at", "datetime", true)
        assertColumn("dm_conversations", "conversation_type", "varchar", false)
        assertColumn("dm_conversations", "order_id", "varchar", true)
        assertColumn("dm_conversations", "direct_pair_key", "varchar", true)
        assertColumn("payment_events", "payload", "longtext", false)
        assertColumn("payment_compensation_cases", "payment_id", "varchar", false)
        assertColumn("payment_compensation_cases", "amount_minor", "bigint", false)
        assertColumn("payment_compensation_cases", "currency", "char", false)
        assertColumn("payment_compensation_cases", "status", "varchar", false)

        jdbcTemplate.update(
            "INSERT INTO doctor_institution_project_configs (id, doctor_id, institution_project_id) VALUES (?, ?, ?)",
            "legacy-config", "legacy-doctor", "legacy-institution-project"
        )
        assertEquals(
            BigDecimal("0.00"),
            jdbcTemplate.queryForObject(
                "SELECT medical_list_price FROM doctor_institution_project_configs WHERE id = 'legacy-config'",
                BigDecimal::class.java
            )
        )

        insertMinimalOrder(jdbcTemplate, "legacy-default-order")
        assertEquals(
            "LEGACY_MEDICAL",
            jdbcTemplate.queryForObject(
                "SELECT payment_flow FROM orders WHERE id = 'legacy-default-order'",
                String::class.java
            )
        )

        assertConstraintViolation {
            insertPricedOrder(jdbcTemplate, "negative-rate", -1, 1)
        }
        assertConstraintViolation {
            insertPricedOrder(jdbcTemplate, "rate-over-limit", 10_001, 1)
        }
        assertConstraintViolation {
            insertPricedOrder(jdbcTemplate, "zero-service-fee", 1_000, 0)
        }
        insertPricedOrder(jdbcTemplate, "valid-service-fee", 10_000, 1)

        assertEquals(listOf("direct_pair_key"), indexColumns("uk_dm_direct_pair"))
        assertEquals(listOf("order_id"), indexColumns("uk_dm_order_service_conversation"))
        assertEquals(
            emptyList<String>(),
            nonUniqueIndexesStartingWith("order_id"),
            "the unique order-service key already supplies the order_id access path, so no ordinary order_id-first index should exist"
        )
    }

    @Test
    fun `conversation schema preserves direct pairs and enforces order service scope`() {
        insertMinimalOrder(jdbcTemplate, "service-order-one")
        insertMinimalOrder(jdbcTemplate, "service-order-two")

        jdbcTemplate.update(
            "INSERT INTO dm_conversations (id, user_a_id, user_b_id) VALUES (?, ?, ?)",
            "direct-one", "user-z", "user-a"
        )
        assertEquals(
            "user-a:user-z",
            jdbcTemplate.queryForObject(
                "SELECT direct_pair_key FROM dm_conversations WHERE id = 'direct-one'",
                String::class.java
            )
        )
        assertConstraintViolation {
            jdbcTemplate.update(
                "INSERT INTO dm_conversations (id, user_a_id, user_b_id) VALUES (?, ?, ?)",
                "direct-reversed", "user-a", "user-z"
            )
        }

        insertOrderServiceConversation("order-service-one", "service-order-one")
        assertConstraintViolation {
            insertOrderServiceConversation("order-service-duplicate", "service-order-one")
        }
        insertOrderServiceConversation("order-service-two", "service-order-two")
        assertConstraintViolation {
            jdbcTemplate.update(
                """
                INSERT INTO dm_conversations (id, conversation_type, order_id, user_a_id, user_b_id)
                VALUES ('direct-with-order', 'DIRECT', 'service-order-one', 'user-a', 'user-b')
                """.trimIndent()
            )
        }
        assertConstraintViolation {
            jdbcTemplate.update(
                """
                INSERT INTO dm_conversations (id, conversation_type, order_id, user_a_id, user_b_id)
                VALUES ('service-without-order', 'ORDER_SERVICE', NULL, 'user-a', 'user-b')
                """.trimIndent()
            )
        }
        assertConstraintViolation {
            insertOrderServiceConversation("service-missing-order", "missing-order")
        }
    }

    @Test
    fun `native direct get or create converges under real mysql concurrency`() {
        val executor = Executors.newFixedThreadPool(2)
        val firstInsertCompleted = CountDownLatch(1)
        val releaseFirstTransaction = CountDownLatch(1)
        val secondInsertStarted = CountDownLatch(1)
        try {
            val first = executor.submit<String> {
                TransactionTemplate(transactionManager).execute {
                    conversationRepository.insertDirectIfAbsent(
                        id = "concurrent-direct-1",
                        userAId = "concurrent-user-a",
                        userBId = "concurrent-user-b",
                        createdAt = LocalDateTime.now(),
                        updatedAt = LocalDateTime.now()
                    )
                    firstInsertCompleted.countDown()
                    check(releaseFirstTransaction.await(10, TimeUnit.SECONDS))
                    conversationRepository.findDirectByPairForUpdate(
                        "concurrent-user-a",
                        "concurrent-user-b"
                    )?.id ?: error("DIRECT_CONVERSATION_NOT_FOUND")
                }!!
            }
            assertTrue(firstInsertCompleted.await(10, TimeUnit.SECONDS))

            val second = executor.submit<String> {
                TransactionTemplate(transactionManager).execute {
                    secondInsertStarted.countDown()
                    conversationRepository.insertDirectIfAbsent(
                        id = "concurrent-direct-2",
                        userAId = "concurrent-user-a",
                        userBId = "concurrent-user-b",
                        createdAt = LocalDateTime.now(),
                        updatedAt = LocalDateTime.now()
                    )
                    conversationRepository.findDirectByPairForUpdate(
                        "concurrent-user-a",
                        "concurrent-user-b"
                    )?.id ?: error("DIRECT_CONVERSATION_NOT_FOUND")
                }!!
            }
            assertTrue(secondInsertStarted.await(10, TimeUnit.SECONDS))
            assertTrue(
                awaitTableLockWaiters(tableName = "dm_conversations", expected = 1),
                "the second native DIRECT upsert must overlap and wait on the first uncommitted unique key"
            )

            releaseFirstTransaction.countDown()
            val conversationIds = listOf(first, second).map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, conversationIds.toSet().size)
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dm_conversations WHERE direct_pair_key = 'concurrent-user-a:concurrent-user-b'",
                    Int::class.java
                )
            )
        } finally {
            releaseFirstTransaction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `order row lock makes concurrent order service creation converge`() {
        insertActivatedTravelOrder(jdbcTemplate, "concurrent-service-order")
        val executor = Executors.newFixedThreadPool(2)
        val lockExecutor = Executors.newSingleThreadExecutor()
        val orderLocked = CountDownLatch(1)
        val releaseOrder = CountDownLatch(1)
        try {
            val holder = lockExecutor.submit<Unit> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.queryForObject(
                        "SELECT id FROM orders WHERE id = 'concurrent-service-order' FOR UPDATE",
                        String::class.java
                    )
                    orderLocked.countDown()
                    check(releaseOrder.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(orderLocked.await(10, TimeUnit.SECONDS))

            val results = (1..2).map {
                executor.submit<String> {
                    orderServiceConversationService.getOrCreate(
                        orderId = "concurrent-service-order",
                        userId = "service-user"
                    ).id
                }
            }

            assertTrue(
                awaitTableLockWaiters(tableName = "orders", expected = 2),
                "both order-service creators must wait on the locked order row"
            )
            releaseOrder.countDown()
            holder.get(10, TimeUnit.SECONDS)
            val conversationIds = results.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(1, conversationIds.toSet().size)
            assertEquals(
                1,
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM dm_conversations WHERE order_id = 'concurrent-service-order'",
                    Int::class.java
                )
            )
        } finally {
            releaseOrder.countDown()
            executor.shutdownNow()
            lockExecutor.shutdownNow()
        }
    }

    @Test
    fun `legacy liability repository queries execute conservatively on mysql`() {
        insertMinimalOrder(jdbcTemplate, "liability-order")

        insertPayment("payment-fully-refunded", "STRIPE", "SUCCEEDED", 100, 100)
        assertEquals(0, countStripePaymentLiabilities(), "a fully refunded successful payment is settled")

        insertPayment("payment-in-flight", " stripe ", " created ", 100, 0)
        assertEquals(1, countStripePaymentLiabilities(), "an in-flight Stripe payment is actionable")

        insertPayment("payment-refundable", "STRIPE", "SUCCEEDED", 100, 0)
        assertEquals(2, countStripePaymentLiabilities(), "an unrefunded successful Stripe payment is actionable")

        insertPayment("payment-unknown", "STRIPE", "UNKNOWN_PROVIDER_STATUS", 100, 0)
        assertEquals(3, countStripePaymentLiabilities(), "an unknown Stripe status is actionable")

        insertPayment("payment-other-provider", "ALIPAY_PLUS", "CREATED", 100, 0)
        assertEquals(3, countStripePaymentLiabilities(), "another provider must not affect Stripe liabilities")

        jdbcTemplate.update(
            """
            INSERT INTO refunds (id, order_id, user_id, amount, requested_amount_minor, reason)
            VALUES ('legacy-refund', 'liability-order', 'legacy-user', 1.00, 100, 'legacy liability')
            """.trimIndent()
        )
        insertRefundItem("refund-item-complete", "STRIPE", "SUCCEEDED")
        assertEquals(0, countStripeRefundLiabilities(), "a successful Stripe refund item is settled")

        insertRefundItem("refund-item-open", " stripe ", "CREATED")
        assertEquals(1, countStripeRefundLiabilities(), "an unresolved Stripe refund item is actionable")

        insertRefundItem("refund-item-other-provider", "ALIPAY_PLUS", "CREATED")
        assertEquals(1, countStripeRefundLiabilities(), "another provider must not affect Stripe refund liabilities")
    }

    @Test
    fun `longtext mapping stores a large non json webhook body byte for byte`() {
        val rawPayload = "paymentId=pay%2B001&memo=" + "国际支付宝+raw&".repeat(7_000) + "signature=a%2Fb%3D"
        paymentEventRepository.saveAndFlush(
            PaymentEventEntity(
                id = "raw-form-event",
                provider = "ALIPAY_PLUS",
                providerEventId = "raw-form-provider-event",
                eventType = "PAYMENT_RESULT",
                payload = rawPayload
            )
        )

        val storedPayload = jdbcTemplate.queryForObject(
            "SELECT payload FROM payment_events WHERE id = 'raw-form-event'",
            String::class.java
        )
        val storedBytes = jdbcTemplate.queryForObject(
            "SELECT payload FROM payment_events WHERE id = 'raw-form-event'"
        ) { result, _ ->
            result.getBytes(1)
        }!!

        assertEquals(rawPayload, storedPayload)
        assertArrayEquals(rawPayload.toByteArray(StandardCharsets.UTF_8), storedBytes)
        assertEquals(rawPayload, paymentEventRepository.findById("raw-form-event").orElseThrow().payload)
    }

    @Test
    fun `V31 anomalies become admin-discoverable compensation cases and price migration preserves remediable values`() {
        val upgradeJdbc = JdbcTemplate(
            DriverManagerDataSource(v31UpgradeMysql.jdbcUrl, v31UpgradeMysql.username, v31UpgradeMysql.password)
        )
        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)
        migrate(v31UpgradeMysql, legacyMigrationLocation, target = "31")
        insertMinimalOrder(upgradeJdbc, "v31-service-order")
        upgradeJdbc.update(
            """
            INSERT INTO doctor_institution_project_configs
                (id, doctor_id, institution_project_id, medical_list_price)
            VALUES ('v31-fractional-price', 'doctor-v31', 'project-v31', 1000.0050),
                   ('v31-out-of-range-price', 'doctor-v31', 'project-v31-overflow', 100000000.0000)
            """.trimIndent()
        )
        upgradeJdbc.update(
            """
            INSERT INTO payments (
                id, order_id, user_id, amount, method, status, failure_code, failure_message,
                provider_payment_id, payment_type, provider, currency, amount_minor, refunded_amount_minor
            ) VALUES
                ('v31-duplicate-payment', 'v31-service-order', 'v31-user', 400.0000, 'ONLINE', 'SUCCESS',
                 'DUPLICATE_PAYMENT_SUCCEEDED', 'late duplicate', 'alipay-v31-duplicate',
                 'TRAVEL_GROUND_SERVICE_FEE', 'ALIPAY_PLUS', 'usd', 40000, 0),
                ('v31-unactivatable-payment', 'v31-service-order', 'v31-user', 400.0000, 'ONLINE', 'SUCCEEDED',
                 'PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE', 'cancelled before callback', 'alipay-v31-unactivatable',
                 'TRAVEL_GROUND_SERVICE_FEE', 'ALIPAY_PLUS', 'USD', 40000, 0)
            """.trimIndent()
        )

        migrate(v31UpgradeMysql, legacyMigrationLocation)

        val cases = upgradeJdbc.query(
            """
            SELECT payment_id, order_id, user_id, provider, provider_payment_id,
                   amount_minor, currency, reason_code, status, idempotency_key
            FROM payment_compensation_cases
            ORDER BY payment_id
            """.trimIndent()
        ) { result, _ ->
            CompensationCaseSnapshot(
                paymentId = result.getString("payment_id"),
                orderId = result.getString("order_id"),
                userId = result.getString("user_id"),
                provider = result.getString("provider"),
                providerPaymentId = result.getString("provider_payment_id"),
                amountMinor = result.getLong("amount_minor"),
                currency = result.getString("currency"),
                reasonCode = result.getString("reason_code"),
                status = result.getString("status"),
                idempotencyKey = result.getString("idempotency_key")
            )
        }
        assertEquals(
            listOf(
                CompensationCaseSnapshot(
                    paymentId = "v31-duplicate-payment",
                    orderId = "v31-service-order",
                    userId = "v31-user",
                    provider = "ALIPAY_PLUS",
                    providerPaymentId = "alipay-v31-duplicate",
                    amountMinor = 40_000,
                    currency = "USD",
                    reasonCode = "DUPLICATE_PAYMENT_SUCCEEDED",
                    status = "PENDING_REVIEW",
                    idempotencyKey = "payment-compensation-v31-duplicate-payment"
                ),
                CompensationCaseSnapshot(
                    paymentId = "v31-unactivatable-payment",
                    orderId = "v31-service-order",
                    userId = "v31-user",
                    provider = "ALIPAY_PLUS",
                    providerPaymentId = "alipay-v31-unactivatable",
                    amountMinor = 40_000,
                    currency = "USD",
                    reasonCode = "PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE",
                    status = "PENDING_REVIEW",
                    idempotencyKey = "payment-compensation-v31-unactivatable-payment"
                )
            ),
            cases
        )
        assertEquals(
            BigDecimal("1000.01"),
            upgradeJdbc.queryForObject(
                "SELECT medical_list_price FROM doctor_institution_project_configs WHERE id = 'v31-fractional-price'",
                BigDecimal::class.java
            )
        )
        assertEquals(
            BigDecimal("100000000.00"),
            upgradeJdbc.queryForObject(
                "SELECT medical_list_price FROM doctor_institution_project_configs WHERE id = 'v31-out-of-range-price'",
                BigDecimal::class.java
            )
        )
    }

    @Test
    fun `V28 legacy rows upgrade through V32 without business cleanup or text changes`() {
        val upgradeJdbc = JdbcTemplate(
            DriverManagerDataSource(upgradeMysql.jdbcUrl, upgradeMysql.username, upgradeMysql.password)
        )
        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)
        migrate(upgradeMysql, legacyMigrationLocation, target = "28")

        upgradeJdbc.update(
            """
            INSERT INTO orders (
                id, order_no, user_id, project_name, currency, price, total_amount_minor,
                paid_amount, paid_amount_minor, consultation_fee, consultation_fee_minor,
                discount_amount, discount_amount_minor, quantity,
                remaining_amount, remaining_amount_minor, status
            ) VALUES (
                'legacy-order', 'LEGACY-ORDER-001', 'legacy-user', 'Legacy medical project', 'USD', 123.45, 12345,
                10.00, 1000, 20.00, 2000,
                3.00, 300, 1,
                110.45, 11045, 'PENDING_BALANCE_PAYMENT'
            )
            """.trimIndent()
        )
        upgradeJdbc.update(
            """
            INSERT INTO dm_conversations (id, user_a_id, user_b_id, last_message)
            VALUES ('legacy-direct', 'legacy-user-z', 'legacy-user-a', 'preserve me')
            """.trimIndent()
        )
        upgradeJdbc.update(
            """
            INSERT INTO payments (
                id, order_id, user_id, amount, method, status, payment_type, provider,
                currency, amount_minor, refunded_amount_minor
            ) VALUES (
                'legacy-payment', 'legacy-order', 'legacy-user', 10.5000, 'CARD', 'SUCCESS',
                'CONSULTATION_FEE', 'STRIPE', 'USD', 1050, 0
            )
            """.trimIndent()
        )
        upgradeJdbc.update(
            """
            INSERT INTO payment_events (
                id, payment_id, provider, provider_event_id, event_type, payload, signature_valid
            ) VALUES (
                'legacy-event', 'legacy-payment', 'STRIPE', 'legacy-event-provider-id',
                'payment_intent.succeeded', ?, 1
            )
            """.trimIndent(),
            "{\"legacy\":true,\"amount\":1050}"
        )
        val legacyJsonBeforeUpgrade = upgradeJdbc.queryForObject(
            "SELECT CAST(payload AS CHAR CHARACTER SET utf8mb4) FROM payment_events WHERE id = 'legacy-event'",
            String::class.java
        )

        migrate(upgradeMysql, legacyMigrationLocation)

        val orderSnapshot = upgradeJdbc.queryForObject(
            """
            SELECT status, payment_flow, total_amount_minor, paid_amount_minor,
                   consultation_fee_minor, discount_amount_minor, remaining_amount_minor
            FROM orders WHERE id = 'legacy-order'
            """.trimIndent()
        ) { result, _ ->
            LegacyOrderSnapshot(
                status = result.getString("status"),
                paymentFlow = result.getString("payment_flow"),
                totalAmountMinor = result.getLong("total_amount_minor"),
                paidAmountMinor = result.getLong("paid_amount_minor"),
                consultationFeeMinor = result.getLong("consultation_fee_minor"),
                discountAmountMinor = result.getLong("discount_amount_minor"),
                remainingAmountMinor = result.getLong("remaining_amount_minor")
            )
        }
        assertEquals(
            LegacyOrderSnapshot(
                status = "PENDING_BALANCE_PAYMENT",
                paymentFlow = "LEGACY_MEDICAL",
                totalAmountMinor = 12345,
                paidAmountMinor = 1000,
                consultationFeeMinor = 2000,
                discountAmountMinor = 300,
                remainingAmountMinor = 11045
            ),
            orderSnapshot
        )

        val paymentSnapshot = upgradeJdbc.queryForObject(
            "SELECT status, amount, amount_minor, provider FROM payments WHERE id = 'legacy-payment'"
        ) { result, _ ->
            LegacyPaymentSnapshot(
                status = result.getString("status"),
                amount = result.getBigDecimal("amount"),
                amountMinor = result.getLong("amount_minor"),
                provider = result.getString("provider")
            )
        }!!
        assertEquals("SUCCESS", paymentSnapshot.status)
        assertEquals("10.5000", paymentSnapshot.amount.toPlainString())
        assertEquals(1050, paymentSnapshot.amountMinor)
        assertEquals("STRIPE", paymentSnapshot.provider)

        assertEquals(
            LegacyConversationSnapshot(
                conversationType = "DIRECT",
                orderId = null,
                directPairKey = "legacy-user-a:legacy-user-z",
                lastMessage = "preserve me"
            ),
            upgradeJdbc.queryForObject(
                "SELECT conversation_type, order_id, direct_pair_key, last_message FROM dm_conversations WHERE id = 'legacy-direct'"
            ) { result, _ ->
                LegacyConversationSnapshot(
                    conversationType = result.getString("conversation_type"),
                    orderId = result.getString("order_id"),
                    directPairKey = result.getString("direct_pair_key"),
                    lastMessage = result.getString("last_message")
                )
            }
        )
        assertEquals(
            legacyJsonBeforeUpgrade,
            upgradeJdbc.queryForObject(
                "SELECT payload FROM payment_events WHERE id = 'legacy-event'",
                String::class.java
            )
        )
        assertEquals(1, rowCount(upgradeJdbc, "orders"))
        assertEquals(1, rowCount(upgradeJdbc, "dm_conversations"))
        assertEquals(1, rowCount(upgradeJdbc, "payments"))
        assertEquals(1, rowCount(upgradeJdbc, "payment_events"))
    }

    private fun assertColumn(table: String, column: String, type: String, nullable: Boolean) {
        val metadata = jdbcTemplate.queryForMap(
            """
            SELECT data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            """.trimIndent(),
            table,
            column
        )
        assertEquals(type, metadata["data_type"])
        assertEquals(if (nullable) "YES" else "NO", metadata["is_nullable"])
    }

    private fun assertDecimalScale(table: String, column: String, scale: Int) {
        assertEquals(
            scale,
            jdbcTemplate.queryForObject(
                """
                SELECT numeric_scale
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """.trimIndent(),
                Int::class.java,
                table,
                column
            )
        )
    }

    private fun indexColumns(indexName: String): List<String> = jdbcTemplate.queryForList(
        """
        SELECT column_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'dm_conversations' AND index_name = ?
        ORDER BY seq_in_index
        """.trimIndent(),
        String::class.java,
        indexName
    )

    private fun nonUniqueIndexesStartingWith(columnName: String): List<String> = jdbcTemplate.queryForList(
        """
        SELECT index_name
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'dm_conversations'
          AND column_name = ?
          AND seq_in_index = 1
          AND non_unique = 1
        ORDER BY index_name
        """.trimIndent(),
        String::class.java,
        columnName
    )

    private fun awaitTableLockWaiters(tableName: String, expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiters = DriverManager.getConnection(
                freshMysql.jdbcUrl,
                "root",
                freshMysql.password
            ).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blocking
                      ON blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE()
                      AND requested.OBJECT_NAME = ?
                      AND blocking.OBJECT_SCHEMA = DATABASE()
                      AND blocking.OBJECT_NAME = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, tableName)
                    statement.setString(2, tableName)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getInt(1)
                    }
                }
            }
            if (waiters >= expected) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun countStripePaymentLiabilities(): Long =
        paymentRepository.countActionableLiabilitiesByProvider(
            provider = "STRIPE",
            inFlightStatuses = listOf("CREATED", "REQUIRES_ACTION", "PROCESSING"),
            refundableStatuses = listOf("SUCCEEDED", "SUCCESS", "PARTIALLY_REFUNDED", "REFUNDED"),
            knownStatuses = PaymentStatus.entries.map(PaymentStatus::name) + "SUCCESS"
        )

    private fun countStripeRefundLiabilities(): Long =
        refundItemRepository.countUnresolvedLiabilitiesByProvider(
            provider = "STRIPE",
            successfulStatuses = listOf("SUCCEEDED", "SUCCESS")
        )

    private fun insertOrderServiceConversation(id: String, orderId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO dm_conversations (id, conversation_type, order_id, user_a_id, user_b_id)
            VALUES (?, 'ORDER_SERVICE', ?, 'service-user', 'service-consultant')
            """.trimIndent(),
            id,
            orderId
        )
    }

    private fun insertPayment(id: String, provider: String, status: String, amountMinor: Long, refundedMinor: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO payments (
                id, order_id, user_id, amount, method, status, payment_type, provider,
                currency, amount_minor, refunded_amount_minor
            ) VALUES (?, 'liability-order', 'legacy-user', ?, 'CARD', ?, 'CONSULTATION_FEE', ?, 'USD', ?, ?)
            """.trimIndent(),
            id,
            BigDecimal.valueOf(amountMinor, 2),
            status,
            provider,
            amountMinor,
            refundedMinor
        )
    }

    private fun insertRefundItem(id: String, provider: String, status: String) {
        jdbcTemplate.update(
            """
            INSERT INTO refund_items (
                id, refund_id, payment_id, provider, currency, amount_minor, status
            ) VALUES (?, 'legacy-refund', 'payment-refundable', ?, 'USD', 100, ?)
            """.trimIndent(),
            id,
            provider,
            status
        )
    }

    private fun assertConstraintViolation(block: () -> Unit) {
        assertThrows(DataAccessException::class.java, block)
    }

    private data class LegacyOrderSnapshot(
        val status: String,
        val paymentFlow: String,
        val totalAmountMinor: Long,
        val paidAmountMinor: Long,
        val consultationFeeMinor: Long,
        val discountAmountMinor: Long,
        val remainingAmountMinor: Long
    )

    private data class LegacyPaymentSnapshot(
        val status: String,
        val amount: BigDecimal,
        val amountMinor: Long,
        val provider: String
    )

    private data class LegacyConversationSnapshot(
        val conversationType: String,
        val orderId: String?,
        val directPairKey: String,
        val lastMessage: String
    )

    private data class CompensationCaseSnapshot(
        val paymentId: String,
        val orderId: String,
        val userId: String,
        val provider: String,
        val providerPaymentId: String,
        val amountMinor: Long,
        val currency: String,
        val reasonCode: String,
        val status: String,
        val idempotencyKey: String
    )

    private fun migrate(container: MySQLContainer<*>, migrationLocation: String, target: String? = null) {
        WorktreeTestDatabase.validateAndPrint(container)
        val configuration = Flyway.configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .locations(migrationLocation)
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    companion object {
        private val DATABASE = WorktreeTestDatabase.databaseName()

        @Container
        @ServiceConnection
        @JvmField
        val freshMysql = TravelPaymentMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @Container
        @JvmField
        val upgradeMysql = TravelPaymentMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @Container
        @JvmField
        val v31UpgradeMysql = TravelPaymentMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        private fun insertMinimalOrder(jdbc: JdbcTemplate, id: String) {
            jdbc.update(
                "INSERT INTO orders (id, user_id, project_name, price, status) VALUES (?, 'legacy-user', 'Project', 1.00, 'PENDING_CONSULTATION_PAYMENT')",
                id
            )
        }

        private fun insertPricedOrder(jdbc: JdbcTemplate, id: String, rateBps: Int, serviceFeeMinor: Long) {
            jdbc.update(
                """
                INSERT INTO orders (
                    id, user_id, project_name, price, status, payment_flow,
                    medical_list_price_minor, platform_service_rate_bps, pricing_policy_revision,
                    travel_ground_service_fee_minor
                ) VALUES (?, 'travel-user', 'Travel service', 100.00, 'PENDING_SERVICE_FEE',
                          'TRAVEL_GROUND_SERVICE_ONLY', 10000, ?, ?, ?)
                """.trimIndent(),
                id,
                rateBps,
                pricingPolicyRevision(rateBps),
                serviceFeeMinor
            )
        }

        private fun insertActivatedTravelOrder(jdbc: JdbcTemplate, id: String) {
            jdbc.update(
                """
                INSERT INTO orders (
                    id, user_id, project_name, price, status, payment_flow,
                    consultant_id, consultant_name, medical_list_price_minor,
                    platform_service_rate_bps, pricing_policy_revision,
                    travel_ground_service_fee_minor, service_activated_at
                ) VALUES (?, 'service-user', 'Travel service', 100.00, 'SERVICE_ACTIVE',
                          'TRAVEL_GROUND_SERVICE_ONLY', 'service-consultant', 'Consultant',
                          10000, 1000, 'travel-ground-service-rate:0.100000', 1000, CURRENT_TIMESTAMP)
                """.trimIndent(),
                id
            )
        }

        private fun pricingPolicyRevision(rateBps: Int): String =
            "travel-ground-service-rate:${BigDecimal.valueOf(rateBps.toLong()).movePointLeft(4).setScale(6).toPlainString()}"

        private fun rowCount(jdbc: JdbcTemplate, table: String): Int {
            require(table in setOf("orders", "dm_conversations", "payments", "payment_events"))
            return jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!
        }
    }
}

class TravelPaymentMySqlContainer(imageName: String) :
    MySQLContainer<TravelPaymentMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
