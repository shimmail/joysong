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
