package com.joysong.server.demo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.file.Path

@Component
@Profile("demo & !prod & !dev")
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(100)
class DemoCatalogRunner(
    private val loader: DemoCatalogLoader,
    private val catalogService: DemoCatalogService,
    private val safetyGate: DemoDatabaseSafetyGate,
    @Value("\${seed.demo.action:VERIFY}") private val configuredAction: String,
    @Value("\${seed.demo.catalog-path:}") private val configuredCatalogPath: String,
    @Value("\${seed.demo.account-password:}") private val accountPassword: String,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val action = runCatching { DemoCatalogAction.valueOf(configuredAction.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("DEMO_DATA_ACTION must be APPLY or VERIFY") }
        val catalogPath = configuredCatalogPath.trim()
        require(catalogPath.isNotEmpty()) { "DEMO_CATALOG_PATH is required" }
        require(accountPassword.length in 12..128) {
            "DEMO_ACCOUNT_PASSWORD must contain 12-128 characters"
        }

        try {
            val loaded = loader.load(Path.of(catalogPath))
            val target = safetyGate.requireSafeTarget("catalog-${action.name.lowercase()}")
            val report = when (action) {
                DemoCatalogAction.APPLY -> catalogService.apply(loaded, accountPassword)
                DemoCatalogAction.VERIFY -> catalogService.verify(loaded, accountPassword)
            }
            val summary = "CATALOG_READY action=${action.name} database=${target.databaseName} " +
                "datasetVersion=${loaded.catalog.datasetVersion} sha256=${loaded.sha256} " +
                "created=${report.createdRows} managed=${report.managedRows}"
            logger.info(summary)
            println(summary)
        } catch (error: Exception) {
            val summary = "CATALOG_FAILED action=${action.name} reason=${error.javaClass.simpleName}"
            logger.error(summary, error)
            println(summary)
            throw error
        }
    }
}

enum class DemoCatalogAction {
    APPLY,
    VERIFY,
}
