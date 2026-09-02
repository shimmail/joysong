package com.joysong.server.demo

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoCatalogLoaderTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val loader = DemoCatalogLoader(objectMapper)
    private lateinit var runtimeDirectory: Path

    @BeforeAll
    fun createRuntimeDirectory() {
        runtimeDirectory = Files.createDirectories(
            Path.of(System.getProperty("user.dir"), ".runtime", "demo-catalog-loader-${UUID.randomUUID()}")
                .toAbsolutePath()
                .normalize(),
        )
    }

    @AfterAll
    fun cleanRuntimeDirectory() {
        deleteTree(runtimeDirectory)
    }

    @Test
    fun `tracked catalog has the approved version and byte digest`() {
        val loaded = loader.load(catalogPath())

        assertEquals(1, loaded.catalog.schemaVersion)
        assertEquals("2026.08.31.1", loaded.catalog.datasetVersion)
        assertEquals("demo-cn-v1", loaded.catalog.namespace)
        assertEquals(EXPECTED_SHA256, loaded.sha256)
    }

    @Test
    fun `unknown root property is rejected`() {
        val root = catalogTree().put("unexpectedRoot", true)

        assertThrows<JsonProcessingException> {
            loader.load(writeCatalog("unknown-root.json", root))
        }
    }

    @Test
    fun `unknown nested property is rejected`() {
        val root = catalogTree()
        (root.path("sharedContent").path("account") as ObjectNode).put("unexpectedNested", true)

        assertThrows<JsonProcessingException> {
            loader.load(writeCatalog("unknown-nested.json", root))
        }
    }

    @Test
    fun `duplicate JSON property is rejected`() {
        val duplicate = Files.readString(catalogPath()).replaceFirst(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1,\n  \"schemaVersion\": 1,",
        )
        val path = runtimeDirectory.resolve("duplicate-property.json")
        Files.writeString(path, duplicate)

        assertThrows<JsonProcessingException> {
            loader.load(path)
        }
    }

    @Test
    fun `missing required property is rejected`() {
        val root = catalogTree().apply { remove("datasetVersion") }

        assertThrows<JsonProcessingException> {
            loader.load(writeCatalog("missing-required.json", root))
        }
    }

    private fun catalogTree(): ObjectNode =
        objectMapper.readTree(catalogPath().toFile()) as ObjectNode

    private fun writeCatalog(name: String, root: ObjectNode): Path =
        runtimeDirectory.resolve(name).also { objectMapper.writeValue(it.toFile(), root) }

    private fun catalogPath(): Path = repositoryRoot().resolve("docs/test/catalog-v1.json")

    private fun repositoryRoot(): Path = generateSequence(
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize(),
    ) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) }
        ?: error("Unable to locate repository root")

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val EXPECTED_SHA256 = "60e778ef88a4c9fd36a3758fe0400a3b10876ec23e3c1c603eeed8f8c12e6c3b"
    }
}
