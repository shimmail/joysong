package com.joysong.server.demo

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Statement
import javax.sql.DataSource

class DemoDatabaseSafetyGateTest {
    @Test
    fun `database safety gate permits public OSS and simulated payment for an isolated demo`() {
        val databaseName = "myapp_worktree_demo_upload"
        val jdbcUrl = "jdbc:mysql://db.internal:3306/$databaseName"
        val dataSource = mockk<DataSource>()
        val connection = mockk<Connection>(relaxed = true)
        val statement = mockk<Statement>(relaxed = true)
        val resultSet = mockk<ResultSet>(relaxed = true)
        val metadata = mockk<DatabaseMetaData>()
        every { dataSource.connection } returns connection
        every { connection.createStatement() } returns statement
        every { statement.executeQuery("SELECT DATABASE()") } returns resultSet
        every { resultSet.next() } returns true
        every { resultSet.getString(1) } returns databaseName
        every { connection.metaData } returns metadata
        every { metadata.url } returns jdbcUrl

        val environment = MockEnvironment().apply {
            setActiveProfiles("demo")
            withProperty("oss.enabled", "true")
            withProperty("payment.alipay-plus.simulated-enabled", "true")
            withProperty("spring.jpa.hibernate.ddl-auto", "validate")
            withProperty("spring.flyway.enabled", "true")
            withProperty("spring.flyway.validate-on-migrate", "true")
            withProperty("spring.flyway.baseline-on-migrate", "false")
            withProperty("spring.flyway.clean-disabled", "true")
        }

        val target = DemoDatabaseSafetyGate(
            dataSource = dataSource,
            environment = environment,
            configuredJdbcUrl = jdbcUrl,
            expectedDatabaseName = databaseName,
        ).requireSafeTarget("test")

        assertEquals(DemoDatabaseTarget("db.internal", databaseName), target)
    }
}
