package com.joysong.server.common.initializer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@Tag("mysql-integration")
@Testcontainers
class IdentityDataInitializerPersistenceTest {
    @Test
    fun `initializer persists complete pending V28 project application fixtures`() {
        val jdbc = migrateFreshDatabase()
        seedRequiredDependencies(jdbc)

        IdentityDataInitializer(jdbc, jacksonObjectMapper()).run(emptyArray())

        val platform = projectRequest(jdbc, SeedIds.PLATFORM_PROJECT_REQUEST_ID)
        assertEquals("PLATFORM", platform.requestType)
        assertEquals("PENDING", platform.status)
        assertEquals(SeedIds.DOC_ID_4, platform.doctorId)
        assertNull(platform.institutionId)
        assertNull(platform.projectId)
        assertEquals("热玛吉焕肤疗程", platform.name)
        assertEquals("抗衰紧致", platform.category)
        assertEquals("USD", platform.currency)
        assertJsonArray(listOf("热玛吉", "紧致抗衰"), platform.tags)
        assertEquals(BigDecimal("9800.00"), platform.referencePrice)
        assertJsonArray(listOf("抗衰紧致", "光电美容"), platform.categoryTags)
        assertNull(platform.price)
        assertNull(platform.originalPrice)
        assertNull(platform.isActive)
        assertNull(platform.consultationFee)
        assertNull(platform.commissionRate)
        assertNull(platform.institutionRate)

        val institution = projectRequest(jdbc, SeedIds.INSTITUTION_PROJECT_REQUEST_ID)
        assertEquals("INSTITUTION", institution.requestType)
        assertEquals("PENDING", institution.status)
        assertEquals(SeedIds.DOC_ID_3, institution.doctorId)
        assertEquals(SeedIds.INST_ID_2, institution.institutionId)
        assertEquals(SeedIds.PROJ_ID_1, institution.projectId)
        assertEquals("皮秒焕肤玻尿酸联合方案", institution.name)
        assertEquals("USD", institution.currency)
        assertJsonArray(listOf("玻尿酸", "术后修护"), institution.tags)
        assertNull(institution.referencePrice)
        assertNull(institution.categoryTags)
        assertEquals(BigDecimal("3280.00"), institution.price)
        assertEquals(BigDecimal("3980.00"), institution.originalPrice)
        assertEquals(true, institution.isActive)
        assertEquals(BigDecimal("50.00"), institution.consultationFee)
        assertEquals(BigDecimal("10.00"), institution.commissionRate)
        assertEquals(BigDecimal("40.00"), institution.institutionRate)

        val configPrices = jdbc.query(
            """
            SELECT doctor_id, medical_list_price
            FROM doctor_institution_project_configs
            WHERE institution_project_id = ?
            ORDER BY medical_list_price
            """.trimIndent(),
            { rs, _ -> rs.getString("doctor_id") to rs.getBigDecimal("medical_list_price") },
            SeedIds.IP_ID_1
        ).toMap()
        assertEquals(
            mapOf(
                SeedIds.DOC_ID_1 to BigDecimal("3999.00"),
                SeedIds.DOC_ID_2 to BigDecimal("4299.00")
            ),
            configPrices
        )

        val forbiddenColumns = jdbc.queryForList(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'professional_project_requests'
              AND column_name IN ('rating', 'review_count', 'doctor_ids', 'doctor_bindings',
                                  'platform_rate', 'doctor_rate', 'submitted_by')
            """.trimIndent(),
            String::class.java
        )
        assertTrue(forbiddenColumns.isEmpty())
        assertFalse(platform.notes.contains("评分"))
    }

    private fun migrateFreshDatabase(): JdbcTemplate {
        WorktreeTestDatabase.validateAndPrint(mysql)
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
    }

    private fun seedRequiredDependencies(jdbc: JdbcTemplate) {
        val userIds = listOf(
            SeedIds.DOC_ID_1, SeedIds.DOC_ID_2, SeedIds.DOC_ID_3, SeedIds.DOC_ID_4, SeedIds.DOC_ID_5, SeedIds.DOC_ID_6,
            SeedIds.USER_ID_2, SeedIds.USER_ID_3, SeedIds.CONSULTANT_ID, SeedIds.LEGAL_REP_ID, SeedIds.CS_USER_ID, SeedIds.ADMIN_ID
        )
        userIds.forEach { userId ->
            jdbc.update(
                "INSERT INTO users (id, password_hash, nickname, role) VALUES (?, 'hash', ?, ?)",
                userId,
                "Seed ${userId.takeLast(4)}",
                if (userId == SeedIds.ADMIN_ID) "ADMIN" else "USER"
            )
        }
        listOf(SeedIds.DOC_ID_1, SeedIds.DOC_ID_2, SeedIds.DOC_ID_3, SeedIds.DOC_ID_4, SeedIds.DOC_ID_5, SeedIds.DOC_ID_6)
            .forEach { doctorId ->
                jdbc.update("INSERT INTO doctors (id, name, is_verified) VALUES (?, ?, 1)", doctorId, "Doctor ${doctorId.takeLast(4)}")
            }
        listOf(SeedIds.INST_ID_1, SeedIds.INST_ID_2, SeedIds.INST_ID_3, SeedIds.INST_ID_4)
            .forEach { institutionId ->
                jdbc.update("INSERT INTO institutions (id, name, is_verified) VALUES (?, ?, 1)", institutionId, "Institution ${institutionId.takeLast(4)}")
            }
        jdbc.update(
            "INSERT INTO projects (id, name) VALUES (?, ?), (?, ?)",
            SeedIds.PROJ_ID_1, "玻尿酸填充", SeedIds.PROJ_ID_3, "双眼皮成形"
        )
        jdbc.update(
            "INSERT INTO institution_projects (id, institution_id, project_id, is_active) VALUES (?, ?, ?, 1), (?, ?, ?, 1)",
            SeedIds.IP_ID_1, SeedIds.INST_ID_1, SeedIds.PROJ_ID_1,
            SeedIds.IP_ID_4, SeedIds.INST_ID_1, SeedIds.PROJ_ID_3
        )
        listOf(
            listOf("seed-di-1", SeedIds.DOC_ID_1, SeedIds.INST_ID_1),
            listOf("seed-di-2", SeedIds.DOC_ID_2, SeedIds.INST_ID_1),
            listOf("seed-di-3", SeedIds.DOC_ID_3, SeedIds.INST_ID_2),
            listOf("seed-di-4", SeedIds.DOC_ID_4, SeedIds.INST_ID_4)
        ).forEach { (id, doctorId, institutionId) ->
            jdbc.update(
                "INSERT INTO doctor_institutions (id, doctor_id, institution_id, status) VALUES (?, ?, ?, 'APPROVED')",
                id, doctorId, institutionId
            )
        }
        jdbc.update(
            """
            INSERT INTO doctor_projects
                (doctor_id, project_id, institution_project_id, price, service_description, service_tags)
            VALUES (?, ?, ?, 4999.00, '', '')
            """.trimIndent(),
            SeedIds.DOC_ID_2,
            SeedIds.PROJ_ID_3,
            SeedIds.IP_ID_4
        )
    }

    private fun projectRequest(jdbc: JdbcTemplate, id: String): ProjectRequestSnapshot = jdbc.queryForObject(
        """
        SELECT id, request_type, status, doctor_id, institution_id, project_id, name, category, currency, tags,
               reference_price, category_tags, price, original_price, is_active,
               consultation_fee, commission_rate, institution_rate, notes
        FROM professional_project_requests
        WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            ProjectRequestSnapshot(
                id = rs.getString("id"),
                requestType = rs.getString("request_type"),
                status = rs.getString("status"),
                doctorId = rs.getString("doctor_id"),
                institutionId = rs.getString("institution_id"),
                projectId = rs.getString("project_id"),
                name = rs.getString("name"),
                category = rs.getString("category"),
                currency = rs.getString("currency"),
                tags = rs.getString("tags"),
                referencePrice = rs.getBigDecimal("reference_price"),
                categoryTags = rs.getString("category_tags"),
                price = rs.getBigDecimal("price"),
                originalPrice = rs.getBigDecimal("original_price"),
                isActive = rs.getObject("is_active")?.let { rs.getBoolean("is_active") },
                consultationFee = rs.getBigDecimal("consultation_fee"),
                commissionRate = rs.getBigDecimal("commission_rate"),
                institutionRate = rs.getBigDecimal("institution_rate"),
                notes = rs.getString("notes")
            )
        },
        id
    )!!

    private fun assertJsonArray(expected: List<String>, actual: String?) {
        assertEquals(expected, jacksonObjectMapper().readValue(requireNotNull(actual), Array<String>::class.java).toList())
    }

    private data class ProjectRequestSnapshot(
        val id: String,
        val requestType: String,
        val status: String,
        val doctorId: String,
        val institutionId: String?,
        val projectId: String?,
        val name: String?,
        val category: String?,
        val currency: String,
        val tags: String,
        val referencePrice: BigDecimal?,
        val categoryTags: String?,
        val price: BigDecimal?,
        val originalPrice: BigDecimal?,
        val isActive: Boolean?,
        val consultationFee: BigDecimal?,
        val commissionRate: BigDecimal?,
        val institutionRate: BigDecimal?,
        val notes: String
    )

    companion object {
        @Container
        @JvmField
        val mysql = IdentityDataInitializerMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class IdentityDataInitializerMySqlContainer(imageName: String) :
    MySQLContainer<IdentityDataInitializerMySqlContainer>(imageName)
