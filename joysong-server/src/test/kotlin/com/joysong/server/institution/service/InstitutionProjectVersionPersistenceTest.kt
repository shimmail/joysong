package com.joysong.server.institution.service

import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

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
class InstitutionProjectVersionPersistenceTest {
    @Autowired private lateinit var repository: InstitutionProjectRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `versioned institution project rejects a stale update`() {
        val initial = repository.saveAndFlush(project("version-update"))
        val stale = initial.copy()
        val current = repository.saveAndFlush(initial.copy(price = BigDecimal("101.00")))

        assertEquals(0L, stale.version)
        assertEquals(1L, current.version)
        assertThrows(OptimisticLockingFailureException::class.java) {
            repository.saveAndFlush(stale.copy(price = BigDecimal("102.00")))
        }
    }

    @Test
    fun `repository soft delete rejects a stale institution project version`() {
        val initial = repository.saveAndFlush(project("version-delete"))
        val stale = initial.copy()
        repository.saveAndFlush(initial.copy(price = BigDecimal("101.00")))

        assertThrows(OptimisticLockingFailureException::class.java) {
            repository.delete(stale)
            repository.flush()
        }
        assertNull(jdbc.queryForObject("SELECT deleted_at FROM institution_projects WHERE id = 'version-delete'", java.time.LocalDateTime::class.java))
    }

    @Test
    fun `repository soft delete marks the row and increments its version`() {
        val initial = repository.saveAndFlush(project("successful-delete"))

        repository.delete(initial)
        repository.flush()

        assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM institution_projects WHERE id = 'successful-delete'", java.time.LocalDateTime::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM institution_projects WHERE id = 'successful-delete'", Long::class.java))
        assertTrue(repository.findById("successful-delete").isEmpty)
    }

    private fun project(id: String) = InstitutionProjectEntity(
        id = id,
        institutionId = "institution-$id",
        projectId = "project-$id",
        price = BigDecimal("100.00"),
        coverImage = null,
        images = null
    )

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql = InstitutionProjectVersionMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class InstitutionProjectVersionMySqlContainer(imageName: String) :
    MySQLContainer<InstitutionProjectVersionMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
