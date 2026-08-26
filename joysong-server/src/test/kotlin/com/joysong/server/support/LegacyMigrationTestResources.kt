package com.joysong.server.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object LegacyMigrationTestResources {
    fun prepare(directory: Path): String {
        migrationFiles.forEach { filename ->
            val resource = "db/migration/$filename"
            requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "Missing migration resource: $resource" }
                .use { Files.copy(it, directory.resolve(filename), StandardCopyOption.REPLACE_EXISTING) }
        }
        return "filesystem:${directory.toAbsolutePath().normalize().toString().replace('\\', '/')}"
    }

    private val migrationFiles = listOf(
        "B26__current_schema.sql",
        "V27__add_consultant_institution_change_requests.sql",
        "V28__expand_professional_project_requests.sql",
        "V29__travel_ground_service_order_flow.sql",
        "V30__order_service_conversations.sql",
        "V31__store_raw_payment_event_payload.sql",
        "V32__payment_compensation_and_usd_price_precision.sql",
        "V32_1__expand_notification_type_columns.sql",
        "V32_2__add_legal_documents.sql",
        "V33__doctor_institution_project_full_edit.sql",
    )
}
