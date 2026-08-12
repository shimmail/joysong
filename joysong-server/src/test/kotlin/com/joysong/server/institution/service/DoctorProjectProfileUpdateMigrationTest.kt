package com.joysong.server.institution.service

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
class DoctorProjectProfileUpdateMigrationTest {
    @Test
    fun `pre V17 profile update history is backfilled before V19 constraints`() {
        val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
        flyway("15").migrate()
        seedV16History(jdbc)

        flyway().migrate()

        assertSnapshot(jdbc, "with-config", "120.00", "12.00", "7.00", "33.00")
        assertSnapshot(jdbc, "without-config", "220.00", "0.00", "0.00", "40.00")
        assertSnapshot(jdbc, "soft-deleted-config", "320.00", "0.00", "0.00", "40.00")
        assertSnapshot(jdbc, "missing-target", "0.00", "0.00", "0.00", "40.00")
        assertEquals(BigDecimal("10.00"), decimal(jdbc, "SELECT current_platform_rate FROM doctor_project_change_requests WHERE id='missing-target'"))
        assertEquals(BigDecimal("50.00"), decimal(jdbc, "SELECT current_doctor_rate FROM doctor_project_change_requests WHERE id='missing-target'"))

        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE doctor_project_change_requests SET current_price=NULL WHERE id='with-config'")
        }
        assertThrows(Exception::class.java) {
            jdbc.update("UPDATE doctor_project_change_requests SET consultation_fee=NULL WHERE id='with-config'")
        }
    }

    private fun flyway(target: String? = null): Flyway {
        val config = Flyway.configure().dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
        if (target != null) config.target(target)
        return config.load()
    }

    private fun seedV16History(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO users (id,password_hash,nickname) VALUES ('migration-doctor','x','D')")
        jdbc.update("INSERT INTO doctors (id,name,is_verified) VALUES ('migration-doctor','D',1)")
        jdbc.update("INSERT INTO institutions (id,name,is_verified) VALUES ('migration-institution','I',1)")
        jdbc.update("INSERT INTO projects (id,name) VALUES ('migration-project-1','P1'),('migration-project-2','P2'),('migration-project-3','P3'),('migration-project-4','P4')")
        jdbc.update("INSERT INTO institution_projects (id,institution_id,project_id,is_active) VALUES ('ip-config','migration-institution','migration-project-1',1),('ip-no-config','migration-institution','migration-project-2',1),('ip-soft-config','migration-institution','migration-project-3',1),('ip-missing','migration-institution','migration-project-4',1)")
        jdbc.update("INSERT INTO doctor_projects (doctor_id,project_id,institution_project_id,price,service_description,service_tags,schedule_note,cover_image,images) VALUES ('migration-doctor','migration-project-1','ip-config',120,'current config','a','s','c','i'),('migration-doctor','migration-project-2','ip-no-config',220,'no config','b','','',''),('migration-doctor','migration-project-3','ip-soft-config',320,'soft config','c','','','')")
        jdbc.update("INSERT INTO doctor_institution_project_configs (id,doctor_id,institution_project_id,consultation_fee,commission_rate,institution_rate,deleted_at) VALUES ('active-config','migration-doctor','ip-config',12,7,33,NULL),('deleted-config','migration-doctor','ip-soft-config',99,9,31,CURRENT_TIMESTAMP)")
        listOf(
            "with-config" to "ip-config",
            "without-config" to "ip-no-config",
            "soft-deleted-config" to "ip-soft-config",
            "missing-target" to "ip-missing"
        ).forEach { (id, ip) ->
            jdbc.update("INSERT INTO doctor_project_change_requests (id,doctor_id,institution_id,institution_project_id,request_type,service_description,service_tags,schedule_note,cover_image,images,status,submitted_by,price_suggestion) VALUES (?,?,?,?, 'PROFILE_UPDATE','requested','tag','','','', 'PENDING',?,999)", id, "migration-doctor", "migration-institution", ip, "migration-doctor")
        }
    }

    private fun assertSnapshot(jdbc: JdbcTemplate, id: String, price: String, fee: String, commission: String, institution: String) {
        assertEquals(BigDecimal(price), decimal(jdbc, "SELECT current_price FROM doctor_project_change_requests WHERE id='$id'"))
        assertEquals(BigDecimal(fee), decimal(jdbc, "SELECT current_consultation_fee FROM doctor_project_change_requests WHERE id='$id'"))
        assertEquals(BigDecimal(commission), decimal(jdbc, "SELECT current_commission_rate FROM doctor_project_change_requests WHERE id='$id'"))
        assertEquals(BigDecimal(institution), decimal(jdbc, "SELECT current_institution_rate FROM doctor_project_change_requests WHERE id='$id'"))
    }

    private fun decimal(jdbc: JdbcTemplate, sql: String): BigDecimal = jdbc.queryForObject(sql, BigDecimal::class.java)!!

    companion object {
        private const val DB_NAME = "myapp_worktree_doctor_profile_update_request_migration"

        @Container @JvmField
        val mysql = DoctorProfileMigrationMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DB_NAME).withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class DoctorProfileMigrationMySqlContainer(imageName: String) : MySQLContainer<DoctorProfileMigrationMySqlContainer>(imageName) {
    override fun start() {
        require(databaseName.startsWith("myapp_worktree_"))
        super.start()
        println("DOCTOR_PROFILE_MIGRATION_DB_HOST=$host:${getMappedPort(3306)}")
        println("DOCTOR_PROFILE_MIGRATION_DB_NAME=$databaseName")
    }
}
