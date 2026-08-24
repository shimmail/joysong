package com.joysong.server.institution.service

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.support.WorktreeTestDatabase
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(properties = [
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration",
    "spring.flyway.baseline-on-migrate=false",
    "spring.flyway.validate-on-migrate=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=never",
    "order.split.platform-rate=10.00",
    "order.split.institution-rate=40.00"
])
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DoctorProjectChangeService::class, DoctorInstitutionRelationshipService::class, OrderSplitRatePolicy::class, OrderSplitProperties::class, ProfileUpdatePersistenceTestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DoctorProjectProfileUpdatePersistenceTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var service: DoctorProjectChangeService

    @Test
    @Order(1)
    fun `fresh migrations and approval atomically update only target doctor`() {
        assertEquals(((1..15).toList() + listOf(17, 18, 19)).map(Int::toString), jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
            String::class.java
        ))
        seed()
        val request = profileRequest()
        val submitted = service.submit(doctorActor(), request)

        assertEquals(BigDecimal("100.00"), submitted.currentPrice)
        assertEquals("before", submitted.currentServiceDescription)
        service.review(legalActor(), submitted.id, "APPROVED", "", false)

        assertEquals(BigDecimal("880.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals("after", text("SELECT service_description FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("30.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("200.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("20.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE doctor_id='doctor-2' AND institution_project_id='ip-1'"))
    }

    @Test
    @Order(2)
    fun `approval exception rolls back both effective tables and request status`() {
        seed()
        val submitted = service.submit(doctorActor(), profileRequest())
        jdbc.update("ALTER TABLE doctor_institution_project_configs ADD CONSTRAINT chk_test_rollback_fee CHECK (consultation_fee <= 20)")

        assertThrows(Exception::class.java) {
            service.review(legalActor(), submitted.id, "APPROVED", "", false)
        }

        assertEquals(BigDecimal("100.00"), decimal("SELECT price FROM doctor_projects WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals(BigDecimal("10.00"), decimal("SELECT consultation_fee FROM doctor_institution_project_configs WHERE doctor_id='doctor-1' AND institution_project_id='ip-1'"))
        assertEquals("PENDING", text("SELECT status FROM doctor_project_change_requests WHERE id='${submitted.id}'"))
    }

    private fun seed() {
        jdbc.update("DELETE FROM doctor_project_change_requests")
        jdbc.update("DELETE FROM doctor_institution_project_configs")
        jdbc.update("DELETE FROM doctor_projects")
        jdbc.update("DELETE FROM doctor_institutions")
        jdbc.update("DELETE FROM institution_projects")
        jdbc.update("DELETE FROM projects")
        jdbc.update("DELETE FROM doctors")
        jdbc.update("DELETE FROM institutions")
        jdbc.update("DELETE FROM users")
        jdbc.update("INSERT INTO users (id,password_hash,nickname) VALUES ('doctor-1','x','D1'),('doctor-2','x','D2'),('legal-1','x','L')")
        jdbc.update("INSERT INTO doctors (id,name,is_verified) VALUES ('doctor-1','D1',1),('doctor-2','D2',1)")
        jdbc.update("INSERT INTO institutions (id,name,is_verified) VALUES ('institution-1','I',1)")
        jdbc.update("INSERT INTO projects (id,name) VALUES ('project-1','P')")
        jdbc.update("INSERT INTO institution_projects (id,institution_id,project_id,is_active) VALUES ('ip-1','institution-1','project-1',1)")
        jdbc.update("INSERT INTO doctor_institutions (id,doctor_id,institution_id,status) VALUES ('di-1','doctor-1','institution-1','APPROVED'),('di-2','doctor-2','institution-1','APPROVED')")
        jdbc.update("INSERT INTO doctor_projects (doctor_id,project_id,institution_project_id,price,service_description,service_tags) VALUES ('doctor-1','project-1','ip-1',100,'before','old'),('doctor-2','project-1','ip-1',200,'other','other')")
        jdbc.update("INSERT INTO doctor_institution_project_configs (id,doctor_id,institution_project_id,consultation_fee,commission_rate,institution_rate) VALUES ('c-1','doctor-1','ip-1',10,5,35),('c-2','doctor-2','ip-1',20,6,34)")
    }

    private fun profileRequest() = DoctorProjectChangeRequest(
        institutionProjectId="ip-1", requestType="PROFILE_UPDATE", serviceDescription="after",
        priceSuggestion=BigDecimal("880.00"), serviceTags=listOf("new"), scheduleNote="schedule",
        coverImage="cover", images=listOf("image"), consultationFee=BigDecimal("30.00"),
        commissionRate=BigDecimal("10.00"), institutionRate=BigDecimal("40.00")
    )

    private fun doctorActor() = ManagementActor("doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), setOf("institution-1"), setOf("doctor-1"))
    private fun legalActor() = ManagementActor("legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null, setOf("institution-1"), emptySet(), emptySet())
    private fun decimal(sql: String): BigDecimal = jdbc.queryForObject(sql, BigDecimal::class.java)!!
    private fun text(sql: String): String = jdbc.queryForObject(sql, String::class.java)!!

    companion object {
        @Container @ServiceConnection @JvmField
        val mysql = DoctorProjectProfileUpdateMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

@TestConfiguration
class ProfileUpdatePersistenceTestConfig {
    @Bean fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

class DoctorProjectProfileUpdateMySqlContainer(imageName: String) : MySQLContainer<DoctorProjectProfileUpdateMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
