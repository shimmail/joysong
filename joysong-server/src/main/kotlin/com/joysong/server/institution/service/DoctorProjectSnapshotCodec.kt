package com.joysong.server.institution.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.format.DateTimeFormatter

class DoctorProjectSnapshotCodec(private val objectMapper: ObjectMapper) {
    private val snapshotReader = objectMapper.copy()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .readerFor(InstitutionProjectSnapshotV2::class.java)

    fun platformInheritanceHash(source: PlatformInheritanceSource): String {
        val canonical = objectMapper.createObjectNode().apply {
            put("schemaVersion", 1)
            put("platformProjectId", required(source.platformProjectId, "platformProjectId"))
            put("name", required(source.name, "name"))
            put("category", required(source.category, "category"))
            putNullable("description", optional(source.description))
            set<JsonNode>("tags", objectMapper.valueToTree(decodeLegacyList(source.tags)))
            putNullable("slogan", optional(source.slogan))
            putNullable("detailContent", optional(source.detailContent))
            putNullable("coverImage", optional(source.coverImage))
            set<JsonNode>("images", objectMapper.valueToTree(decodeLegacyList(source.images)))
        }
        return sha256(objectMapper.writeValueAsBytes(canonical))
    }

    fun baseRevision(source: DoctorProjectRevisionSource): String {
        require(source.institutionProjectVersion >= 0) { "institutionProjectVersion must be non-negative" }
        require(source.platformInheritanceHash.matches(SHA256)) { "platformInheritanceHash is invalid" }
        require((source.configId == null) == (source.configUpdatedAt == null)) {
            "configId and configUpdatedAt must both be null or both be present"
        }
        val canonical = objectMapper.createObjectNode().apply {
            put("schemaVersion", 1)
            put("institutionProjectId", required(source.institutionProjectId, "institutionProjectId"))
            put("institutionId", required(source.institutionId, "institutionId"))
            put("platformProjectId", required(source.platformProjectId, "platformProjectId"))
            put("institutionProjectVersion", source.institutionProjectVersion)
            put("platformInheritanceHash", source.platformInheritanceHash)
            put("doctorProjectUpdatedAt", DateTimeFormatter.ISO_INSTANT.format(source.doctorProjectUpdatedAt))
            putNullable("configId", source.configId?.let { required(it, "configId") })
            putNullable(
                "configUpdatedAt",
                source.configUpdatedAt?.let(DateTimeFormatter.ISO_INSTANT::format)
            )
            put("pricingPolicyRevision", required(source.pricingPolicyRevision, "pricingPolicyRevision"))
        }
        return sha256(objectMapper.writeValueAsBytes(canonical))
    }

    fun encode(snapshot: InstitutionProjectSnapshotV2): String {
        validateSnapshot(objectMapper.valueToTree(snapshot))
        return objectMapper.writeValueAsString(snapshot)
    }

    fun decode(raw: String): InstitutionProjectSnapshotV2 = try {
        val tree = objectMapper.readTree(raw) ?: throw IllegalArgumentException("snapshot is null")
        validateSnapshot(tree)
        snapshotReader.readValue(raw)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        throw IllegalArgumentException("REQUEST_SNAPSHOT_INVALID", e)
    }

    private fun validateSnapshot(root: JsonNode) {
        require(root.isObject) { "snapshot must be an object" }
        root.requireExactKeys(ROOT_KEYS)
        require(root.path("schemaVersion").isIntegralNumber && root.path("schemaVersion").intValue() == 2) {
            "unsupported snapshot schema"
        }

        val association = root.path("association")
        association.requireExactKeys(ASSOCIATION_KEYS)
        ASSOCIATION_KEYS.forEach { key -> requireNonBlankText(association.path(key), key) }

        val raw = root.path("rawOverrides")
        raw.requireExactKeys(RAW_KEYS)
        RAW_TEXT_KEYS.forEach { key -> requireNullableText(raw.path(key), key) }
        RAW_LIST_KEYS.forEach { key ->
            val value = raw.path(key)
            require(value.isNull || value.isArray) { "$key must be an array or null" }
            if (value.isArray) {
                require(value.size() > 0) { "$key must be null instead of an empty raw override array" }
                requireTextArray(value, key)
            }
        }

        val effective = root.path("effective")
        effective.requireExactKeys(EFFECTIVE_KEYS)
        requireNonBlankText(effective.path("name"), "effective.name")
        requireNonBlankText(effective.path("category"), "effective.category")
        listOf("description", "slogan", "detailContent", "coverImage").forEach { key ->
            requireNullableText(effective.path(key), "effective.$key")
        }
        listOf("tags", "images").forEach { key ->
            val value = effective.path(key)
            require(value.isArray) { "effective.$key must be an array" }
            requireTextArray(value, "effective.$key")
        }
        val salesCount = effective.path("salesCount")
        require(salesCount.isIntegralNumber && salesCount.canConvertToInt() && salesCount.intValue() >= 0) {
            "effective.salesCount must be a non-negative integer"
        }

        val source = root.path("source")
        source.requireExactKeys(SOURCE_KEYS)
        require(source.path("institutionProjectVersion").isIntegralNumber &&
            source.path("institutionProjectVersion").longValue() >= 0) {
            "institutionProjectVersion must be non-negative"
        }
        requireNonBlankText(source.path("platformInheritanceHash"), "platformInheritanceHash")
        require(source.path("platformInheritanceHash").textValue().matches(SHA256)) {
            "platformInheritanceHash is invalid"
        }
    }

    private fun JsonNode.requireExactKeys(expected: Set<String>) {
        require(isObject && fieldNames().asSequence().toSet() == expected) {
            "snapshot keys must be exactly $expected"
        }
    }

    private fun requireNonBlankText(value: JsonNode, label: String) {
        require(value.isTextual && value.textValue().isNotBlank()) { "$label must be a nonblank string" }
    }

    private fun requireNullableText(value: JsonNode, label: String) {
        require(value.isNull || value.isTextual) { "$label must be a string or null" }
        if (value.isTextual) require(value.textValue().isNotBlank()) { "$label must be null instead of blank" }
    }

    private fun requireTextArray(value: JsonNode, label: String) {
        value.forEachIndexed { index, item ->
            require(item.isTextual && item.textValue().isNotBlank()) { "$label[$index] must be nonblank text" }
        }
    }

    private fun decodeLegacyList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val values = if (raw.trimStart().startsWith("[")) {
            val node = try {
                objectMapper.readTree(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("invalid list encoding", e)
            }
            require(node.isArray) { "list encoding must be an array" }
            node.map { item ->
                require(item.isTextual) { "list values must be strings" }
                item.textValue()
            }
        } else {
            raw.split(',')
        }
        return values.mapNotNull { optional(it) }
    }

    private fun required(value: String, label: String): String = optional(value)
        ?: throw IllegalArgumentException("$label must not be blank")

    private fun optional(value: String?): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }

    private fun ObjectNode.putNullable(name: String, value: String?) {
        if (value == null) putNull(name) else put(name, value)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val ROOT_KEYS = setOf("schemaVersion", "association", "rawOverrides", "effective", "source")
        val ASSOCIATION_KEYS = setOf("institutionProjectId", "institutionId", "platformProjectId")
        val RAW_KEYS = setOf("name", "category", "description", "tags", "slogan", "detailContent", "coverImage", "images")
        val RAW_TEXT_KEYS = setOf("name", "category", "description", "slogan", "detailContent", "coverImage")
        val RAW_LIST_KEYS = setOf("tags", "images")
        val EFFECTIVE_KEYS = setOf(
            "name", "category", "description", "tags", "slogan", "detailContent", "salesCount", "coverImage", "images"
        )
        val SOURCE_KEYS = setOf("institutionProjectVersion", "platformInheritanceHash")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
