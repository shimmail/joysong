package com.joysong.server.legal.entity

import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.dialect.MySQLDialect
import org.hibernate.mapping.BasicValue
import org.hibernate.type.SqlTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegalDocumentEntityMappingTest {
    @Test
    fun `release enums use varchar JDBC mappings with MySQL`() {
        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", MySQLDialect::class.java.name)
            .build()

        try {
            val metadata = MetadataSources(registry)
                .addAnnotatedClass(LegalDocumentReleaseEntity::class.java)
                .buildMetadata()
            val release = metadata.getEntityBinding(LegalDocumentReleaseEntity::class.java.name)

            listOf("documentType", "status").forEach { propertyName ->
                val mapping = release.getProperty(propertyName).value as BasicValue
                assertEquals(
                    SqlTypes.VARCHAR,
                    mapping.resolve().jdbcType.defaultSqlTypeCode,
                    "$propertyName must validate against the migration VARCHAR column"
                )
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }
}
