package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComparisonRequestBuilderTest {
    private val builder = ComparisonRequestBuilder()

    @Test
    fun `builds a complete request from explicitly named same type candidates`() {
        val result = builder.build(
            content = "Compare Alpha Clinic with Beta Clinic on price",
            targetType = AgentQueryTarget.INSTITUTION,
            candidates = listOf(institution("alpha", "Alpha Clinic"), institution("beta", "Beta Clinic"))
        )

        assertEquals(AgentQueryTarget.INSTITUTION, result.targetType)
        assertEquals(listOf("alpha", "beta"), result.operands.map { it.entityId })
        assertEquals(listOf("PRICE"), result.dimensions)
        assertTrue(result.missingFields.isEmpty())
        assertTrue(result.isComplete)
    }

    @Test
    fun `does not promote ranked candidates that are absent from the request`() {
        val result = builder.build(
            content = "Which clinic is better?",
            targetType = AgentQueryTarget.INSTITUTION,
            candidates = listOf(institution("alpha", "Alpha Clinic"), institution("beta", "Beta Clinic"))
        )

        assertTrue(result.operands.isEmpty())
        assertEquals(AgentQueryTarget.INSTITUTION, result.targetType)
        assertEquals(listOf("CREDENTIALS", "RATING"), result.dimensions)
        assertEquals(setOf(ComparisonMissingField.OPERANDS), result.missingFields)
    }

    @Test
    fun `matches broader institution project subjects against names with institution prefixes`() {
        val result = builder.build(
            content = "机构项目",
            operandContent = "对比热玛吉和玻尿酸 机构项目",
            targetType = AgentQueryTarget.INSTITUTION_PROJECT,
            candidates = listOf(
                item("INSTITUTION_PROJECT", "thermage", "悦美机构 · 热玛吉紧肤"),
                item("INSTITUTION_PROJECT", "filler", "安心诊所 · 玻尿酸填充")
            )
        )

        assertEquals(AgentQueryTarget.INSTITUTION_PROJECT, result.targetType)
        assertEquals(listOf("thermage", "filler"), result.operands.map { it.entityId })
        assertTrue(result.isComplete)
    }

    @Test
    fun `does not broadly promote institution projects without requested subjects`() {
        val result = builder.build(
            content = "对比机构项目",
            targetType = AgentQueryTarget.INSTITUTION_PROJECT,
            candidates = listOf(
                item("INSTITUTION_PROJECT", "thermage", "悦美机构 · 热玛吉紧肤"),
                item("INSTITUTION_PROJECT", "filler", "安心诊所 · 玻尿酸填充")
            )
        )

        assertTrue(result.operands.isEmpty())
        assertEquals(setOf(ComparisonMissingField.OPERANDS), result.missingFields)
    }

    @Test
    fun `applies target defaults without making dimensions a missing field`() {
        val result = builder.build(
            content = "Compare Dr Alpha and Dr Beta",
            targetType = AgentQueryTarget.DOCTOR,
            candidates = listOf(doctor("alpha", "Dr Alpha"), doctor("beta", "Dr Beta"))
        )

        assertEquals(listOf("CREDENTIALS", "RATING"), result.dimensions)
        assertTrue(result.missingFields.isEmpty())
        assertTrue(result.isComplete)
    }

    @Test
    fun `completes an incomplete previous request with one current operand`() {
        val result = builder.build(
            content = "Add Beta Treatment and compare price",
            targetType = null,
            candidates = listOf(project("beta", "Beta Treatment")),
            previous = ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Alpha Treatment")),
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf("CREDENTIALS")
            )
        )

        assertEquals(AgentQueryTarget.PROJECT, result.targetType)
        assertEquals(listOf("alpha", "beta"), result.operands.map { it.entityId })
        assertEquals(listOf("CREDENTIALS", "PRICE"), result.dimensions)
        assertTrue(result.missingFields.isEmpty())
    }

    @Test
    fun `completes an incomplete previous request when current operand is duplicated across sources`() {
        val result = builder.build(
            content = "Add Beta Treatment and compare price",
            targetType = null,
            candidates = listOf(project("beta", "Beta Treatment")),
            contextCandidates = listOf(project("beta", "Beta Treatment")),
            previous = ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Alpha Treatment")),
                targetType = AgentQueryTarget.PROJECT
            )
        )

        assertEquals(AgentQueryTarget.PROJECT, result.targetType)
        assertEquals(listOf("alpha", "beta"), result.operands.map { it.entityId })
        assertTrue(result.missingFields.isEmpty())
    }

    @Test
    fun `does not append one operand to a complete previous request`() {
        val result = builder.build(
            content = "Compare Gamma Treatment",
            targetType = null,
            candidates = listOf(project("gamma", "Gamma Treatment")),
            previous = ComparisonRequest(
                operands = listOf(
                    ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Alpha Treatment"),
                    ComparisonOperand(AgentQueryTarget.PROJECT, "beta", "Beta Treatment")
                ),
                targetType = AgentQueryTarget.PROJECT
            )
        )

        assertEquals(listOf("gamma"), result.operands.map { it.entityId })
        assertEquals(setOf(ComparisonMissingField.OPERANDS), result.missingFields)
    }

    @Test
    fun `merges dimensions stably and lets current allowlisted constraints win`() {
        val result = builder.build(
            content = "Compare Alpha Treatment and Beta Treatment: price, credentials, budget 300-500, recovery 3 days",
            targetType = AgentQueryTarget.PROJECT,
            candidates = listOf(project("alpha", "Alpha Treatment"), project("beta", "Beta Treatment")),
            previous = ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.PROJECT, "old", "Old Treatment")),
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf("RATING", "PRICE"),
                constraints = linkedMapOf("city" to "Shanghai", "budgetMin" to "100")
            ),
            detectedCities = listOf("Beijing")
        )

        assertEquals(listOf("RATING", "PRICE", "CREDENTIALS"), result.dimensions)
        assertEquals(
            linkedMapOf("city" to "Beijing", "budgetMin" to "300", "budgetMax" to "500", "downtimeDays" to "3"),
            result.constraints
        )
    }

    @Test
    fun `normalizes recovered requests and enforces every capacity limit`() {
        val result = builder.normalize(
            ComparisonRequest(
                operands = (1..5).map { index ->
                    ComparisonOperand(AgentQueryTarget.PROJECT, " p$index ", " Project $index ")
                },
                targetType = AgentQueryTarget.PROJECT,
                dimensions = listOf(" PRICE ", "RATING", "CREDENTIALS", "PRICE", "", "UNKNOWN"),
                constraints = linkedMapOf(
                    "city" to " ${"x".repeat(121)} ",
                    "budgetMin" to " ${"1".repeat(121)} ",
                    "budgetMax" to " ${"2".repeat(121)} ",
                    "downtimeDays" to " ${"3".repeat(121)} ",
                    "unknown" to "discard"
                ),
                missingFields = emptySet()
            )
        )

        assertEquals(listOf("p1", "p2", "p3", "p4"), result.operands.map { it.entityId })
        assertTrue(result.dimensions.size <= 5)
        assertTrue(result.constraints.size <= 8)
        assertTrue(result.operands.all { it.entityId.length <= 100 && it.displayName.length <= 120 })
        assertTrue(result.dimensions.all { it.length <= 40 })
        assertTrue(result.constraints.all { (key, value) -> key.length <= 40 && value.length <= 120 })
        assertTrue(result.missingFields.isEmpty())
    }

    @Test
    fun `drops mixed target operands unknown constraints and health text`() {
        val result = builder.normalize(
            ComparisonRequest(
                operands = listOf(
                    ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Alpha Treatment"),
                    ComparisonOperand(AgentQueryTarget.DOCTOR, "doctor", "Dr Beta"),
                    ComparisonOperand(AgentQueryTarget.PROJECT, "beta", "Beta Treatment")
                ),
                targetType = AgentQueryTarget.PROJECT,
                constraints = linkedMapOf(
                    "city" to "pregnant",
                    "budgetMin" to "10000",
                    "healthStatus" to "pregnant"
                ),
                missingFields = setOf(ComparisonMissingField.TARGET_TYPE)
            )
        )

        assertEquals(listOf("alpha", "beta"), result.operands.map { it.entityId })
        assertEquals(linkedMapOf("budgetMin" to "10000"), result.constraints)
        assertTrue(result.missingFields.isEmpty())
        assertFalse(ComparisonMissingField.TARGET_TYPE in result.missingFields)
    }

    @Test
    fun `does not append an incomplete inherited operand when current operands are mixed`() {
        val result = builder.build(
            content = "Compare New Clinic",
            targetType = AgentQueryTarget.INSTITUTION,
            candidates = listOf(institution("new", "New Clinic")),
            contextCandidates = listOf(doctor("doctor", "Dr Context")),
            previous = ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.INSTITUTION, "old", "Old Clinic")),
                targetType = AgentQueryTarget.INSTITUTION
            )
        )

        assertEquals(listOf("new"), result.operands.map { it.entityId })
        assertEquals(setOf(ComparisonMissingField.OPERANDS), result.missingFields)
    }

    @Test
    fun `ignores blank id and name context candidates before inference and merge`() {
        val result = builder.build(
            content = "Compare Alpha Treatment",
            targetType = null,
            candidates = listOf(project("alpha", "Alpha Treatment")),
            contextCandidates = listOf(
                item("DOCTOR", " ", "Dr Missing Id"),
                item("DOCTOR", "doctor", " ")
            ),
            previous = ComparisonRequest(
                operands = listOf(ComparisonOperand(AgentQueryTarget.INSTITUTION, "old", "Old Clinic")),
                targetType = AgentQueryTarget.INSTITUTION
            )
        )

        assertEquals(AgentQueryTarget.PROJECT, result.targetType)
        assertEquals(listOf("alpha"), result.operands.map { it.entityId })
        assertEquals(setOf(ComparisonMissingField.OPERANDS), result.missingFields)
    }

    @Test
    fun `rejects recovered health city text and nonnumeric constraint values`() {
        listOf("diabetes", "高血压").forEach { unsafeCity ->
            val result = builder.normalize(
                ComparisonRequest(
                    operands = listOf(
                        ComparisonOperand(AgentQueryTarget.PROJECT, "alpha", "Alpha Treatment"),
                        ComparisonOperand(AgentQueryTarget.PROJECT, "beta", "Beta Treatment")
                    ),
                    targetType = AgentQueryTarget.PROJECT,
                    constraints = linkedMapOf(
                        "city" to unsafeCity,
                        "budgetMin" to "ten thousand",
                        "budgetMax" to "500x",
                        "downtimeDays" to "three"
                    )
                )
            )

            assertTrue(result.constraints.isEmpty())
        }
    }

    private fun institution(id: String, name: String) = item("INSTITUTION", id, name)

    private fun doctor(id: String, name: String) = item("DOCTOR", id, name)

    private fun project(id: String, name: String) = item("PROJECT", id, name)

    private fun item(type: String, id: String, name: String) = AgentCatalogItemResponse(
        type = type,
        id = id,
        name = name,
        subtitle = "",
        summary = "",
        attributes = emptyMap()
    )
}
