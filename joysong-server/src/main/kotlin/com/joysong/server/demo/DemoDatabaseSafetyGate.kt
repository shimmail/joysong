package com.joysong.server.demo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI
import javax.sql.DataSource

data class DemoDatabaseTarget(
    val host: String,
    val databaseName: String,
)

@Component
@Profile("demo")
class DemoDatabaseSafetyGate(
    private val dataSource: DataSource,
    private val environment: Environment,
    @Value("\${spring.datasource.url:}") private val configuredJdbcUrl: String,
    @Value("\${seed.demo.expected-database-name:}") private val expectedDatabaseName: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun requireSafeTarget(stage: String): DemoDatabaseTarget {
        val expected = expectedDatabaseName.trim()
        check(expected.isNotEmpty()) { "DEMO_DATABASE_NAME is required in the demo profile" }
        check(expected.startsWith(SAFE_DATABASE_PREFIX)) {
            "Demo database name must start with $SAFE_DATABASE_PREFIX"
        }
        val activeProfiles = environment.activeProfiles.map(String::lowercase).toSet()
        check("prod" !in activeProfiles && "dev" !in activeProfiles) {
            "The demo profile cannot be combined with prod or dev"
        }
        DEMO_DISABLED_PROPERTIES.forEach { property ->
            check(!environment.getProperty(property, Boolean::class.java, false)) {
                "$property must be false in the demo profile"
            }
        }
        DEMO_REQUIRED_PROPERTIES.forEach { (property, required) ->
            check(environment.getProperty(property)?.trim()?.lowercase() == required) {
                "$property must be $required in the demo profile"
            }
        }

        val configuredUrl = configuredJdbcUrl.trim()
        check(!configuredUrl.contains("createDatabaseIfNotExist", ignoreCase = true)) {
            "Demo DB_URL must not contain createDatabaseIfNotExist"
        }
        val configuredTargetBeforeConnection = parseJdbcTarget(configuredUrl)
        check(configuredTargetBeforeConnection.databaseName == expected) {
            "Configured demo database does not match DEMO_DATABASE_NAME"
        }

        val (metadataUrl, actualDatabase) = dataSource.connection.use { connection ->
            val database = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT DATABASE()").use { result ->
                    check(result.next()) { "MySQL did not return SELECT DATABASE()" }
                    result.getString(1)?.trim().orEmpty()
                }
            }
            connection.metaData.url to database
        }
        check(!metadataUrl.contains("createDatabaseIfNotExist=true", ignoreCase = true)) {
            "Demo DB_URL must not enable createDatabaseIfNotExist"
        }
        val configuredTarget = parseJdbcTarget(metadataUrl)
        check(configuredTarget == configuredTargetBeforeConnection) {
            "Configured and connected demo database targets do not match"
        }
        check(actualDatabase.isNotEmpty()) { "MySQL did not report an active database" }
        check(configuredTarget.databaseName == actualDatabase) {
            "JDBC database and SELECT DATABASE() do not match"
        }
        check(actualDatabase == expected) {
            "Actual demo database does not match DEMO_DATABASE_NAME"
        }

        logger.info(
            "Demo database safety gate passed stage={} host={} database={}",
            stage,
            configuredTarget.host,
            actualDatabase,
        )
        return DemoDatabaseTarget(configuredTarget.host, actualDatabase)
    }

    internal fun parseJdbcTarget(jdbcUrl: String): DemoDatabaseTarget {
        check(jdbcUrl.startsWith("jdbc:mysql://", ignoreCase = true)) {
            "Demo profile requires a jdbc:mysql:// DB_URL"
        }
        val uri = URI(jdbcUrl.removePrefix("jdbc:"))
        val host = uri.host?.trim().orEmpty()
        val databaseName = uri.path.orEmpty().removePrefix("/").substringBefore('/').trim()
        check(host.isNotEmpty()) { "DB_URL must include a database host" }
        check(databaseName.isNotEmpty()) { "DB_URL must include a database name" }
        return DemoDatabaseTarget(host, databaseName)
    }

    private companion object {
        const val SAFE_DATABASE_PREFIX = "myapp_worktree_"
        val DEMO_DISABLED_PROPERTIES = listOf(
            // Public media OSS is allowed for an isolated demo. Credentials
            // still come from the ECS RAM role and private storage stays local.
            "aliyun.sms.enabled",
            "security.verification-code.log-for-dev",
            "payment.alipay-plus.auto-pay-on-order-create-enabled",
            "payment.alipay-plus.simulated-enabled",
            "payment.stripe.legacy-enabled",
            "payment.reconciliation.enabled",
            "app.scheduling.enabled",
            "app.account-deletion.enabled",
            "app.account-deletion.allow-commerce-bypass",
        )
        val DEMO_REQUIRED_PROPERTIES = mapOf(
            "spring.jpa.hibernate.ddl-auto" to "validate",
            "spring.flyway.enabled" to "true",
            "spring.flyway.validate-on-migrate" to "true",
            "spring.flyway.baseline-on-migrate" to "false",
            "spring.flyway.clean-disabled" to "true",
        )
    }
}

@Configuration
@Profile("demo")
class DemoFlywaySafetyConfiguration {
    @Bean
    fun demoFlywayMigrationStrategy(safetyGate: DemoDatabaseSafetyGate): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            safetyGate.requireSafeTarget("before-flyway-migrate")
            flyway.migrate()
        }
}
