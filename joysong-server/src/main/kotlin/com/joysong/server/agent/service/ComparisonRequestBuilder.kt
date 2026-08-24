package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import org.springframework.stereotype.Service

@Service
class ComparisonRequestBuilder {
    fun build(
        content: String,
        operandContent: String = content,
        targetType: AgentQueryTarget?,
        candidates: List<AgentCatalogItemResponse>,
        contextCandidates: List<AgentCatalogItemResponse> = emptyList(),
        previous: ComparisonRequest? = null,
        detectedCities: List<String> = emptyList()
    ): ComparisonRequest {
        val inherited = previous?.let(::normalize)
        val currentOperands = (candidates.filter { it.isNamedIn(operandContent, targetType) } + contextCandidates)
            .mapNotNull(::toOperand)
            .distinctBy { "${it.entityType.name}:${it.entityId}" }
        val resolvedTarget = targetType ?: currentOperands.map { it.entityType }.distinct().singleOrNull() ?: inherited?.targetType
        val mergedOperands = when {
            currentOperands.size >= 2 -> currentOperands
            currentOperands.isEmpty() -> inherited?.operands.orEmpty()
            inherited != null && !inherited.isComplete && inherited.targetType == resolvedTarget ->
                inherited.operands + currentOperands
            else -> currentOperands
        }
        val currentDimensions = detectedDimensions(content)
        val mergedDimensions = (inherited?.dimensions.orEmpty() + currentDimensions).distinct()
        val mergedConstraints = inherited?.constraints.orEmpty().toMap(linkedMapOf()).apply {
            putAll(detectedConstraints(content, detectedCities))
        }

        return normalize(
            ComparisonRequest(
                operands = mergedOperands,
                targetType = resolvedTarget,
                dimensions = mergedDimensions,
                constraints = mergedConstraints
            )
        )
    }

    fun normalize(request: ComparisonRequest): ComparisonRequest {
        val cleanedOperands = request.operands.asSequence()
            .map { operand ->
                operand.copy(
                    entityId = operand.entityId.trim().take(MAX_ENTITY_ID_LENGTH),
                    displayName = operand.displayName.trim().take(MAX_DISPLAY_NAME_LENGTH)
                )
            }
            .filter { it.entityId.isNotBlank() && it.displayName.isNotBlank() }
            .distinctBy { "${it.entityType.name}:${it.entityId}" }
            .toList()
        val operandType = request.targetType ?: cleanedOperands.firstOrNull()?.entityType
        val operands = cleanedOperands
            .filter { operandType == null || it.entityType == operandType }
            .take(MAX_OPERANDS)
        val dimensions = request.dimensions.asSequence()
            .map { it.trim().uppercase() }
            .filter { it in allowedDimensions }
            .distinct()
            .take(MAX_DIMENSIONS)
            .toList()
            .ifEmpty { request.targetType?.let { defaultDimensions[it] }.orEmpty() }
        val constraints = linkedMapOf<String, String>().apply {
            request.constraints.forEach { (key, value) ->
                val normalizedKey = key.trim().take(MAX_CONSTRAINT_KEY_LENGTH)
                val normalizedValue = value.trim().take(MAX_CONSTRAINT_VALUE_LENGTH)
                if (
                    normalizedKey in allowedConstraints && normalizedValue.isValidConstraintValue(normalizedKey) &&
                    size < MAX_CONSTRAINTS
                ) {
                    put(normalizedKey, normalizedValue)
                }
            }
        }
        val missing = linkedSetOf<ComparisonMissingField>().apply {
            if (request.targetType == null) add(ComparisonMissingField.TARGET_TYPE)
            if (request.targetType == null || operands.map { it.entityId }.distinct().size < 2) {
                add(ComparisonMissingField.OPERANDS)
            }
        }
        return ComparisonRequest(
            operands = operands,
            targetType = request.targetType,
            dimensions = dimensions,
            constraints = constraints,
            missingFields = missing
        )
    }

    private fun AgentCatalogItemResponse.isNamedIn(content: String, targetType: AgentQueryTarget?): Boolean {
        if (name.isNotBlank() && content.contains(name, ignoreCase = true) ||
            id.isNotBlank() && content.contains(id, ignoreCase = true)
        ) return true
        if (targetType != AgentQueryTarget.INSTITUTION_PROJECT || !type.equals("INSTITUTION_PROJECT", true)) {
            return false
        }
        val projectAlias = name.substringAfter('·', missingDelimiterValue = "").trim()
        if (projectAlias.isBlank()) return false
        return comparisonSubjects(content).any { subject ->
            projectAlias.contains(subject, ignoreCase = true) || subject.contains(projectAlias, ignoreCase = true)
        }
    }

    private fun comparisonSubjects(content: String): List<String> = content
        .replace(comparisonActionPattern, " ")
        .replace(comparisonTargetPattern, " ")
        .split(comparisonSubjectSeparator)
        .map { it.trim().trim('，', ',', '。', '.', '：', ':', '；', ';', '！', '!', '？', '?') }
        .filter { subject -> subject.count { !it.isWhitespace() } >= MIN_COMPARISON_SUBJECT_LENGTH }
        .distinct()

    private fun toOperand(item: AgentCatalogItemResponse): ComparisonOperand? {
        val entityType = runCatching { AgentQueryTarget.valueOf(item.type.uppercase()) }.getOrNull() ?: return null
        val entityId = item.id.trim()
        val displayName = item.name.trim()
        if (entityId.isBlank() || displayName.isBlank()) return null
        return ComparisonOperand(entityType, entityId, displayName)
    }

    private fun detectedDimensions(content: String): List<String> {
        val normalized = content.lowercase()
        return dimensionSignals.mapNotNull { (dimension, terms) ->
            dimension.takeIf { terms.any { normalized.contains(it) } }
        }
    }

    private fun detectedConstraints(content: String, detectedCities: List<String>): Map<String, String> = linkedMapOf<String, String>().apply {
        detectedCities.firstOrNull { it.isNotBlank() }?.let { put("city", it) }
        budgetRangePattern.find(content)?.let { match ->
            put("budgetMin", match.groupValues[1])
            put("budgetMax", match.groupValues[2])
        }
        budgetMinPattern.find(content)?.groupValues?.getOrNull(1)?.let { put("budgetMin", it) }
        budgetMaxPattern.find(content)?.groupValues?.getOrNull(1)?.let { put("budgetMax", it) }
        downtimePattern.find(content)?.groupValues?.getOrNull(1)?.let { put("downtimeDays", it) }
    }

    private fun String.isValidConstraintValue(key: String): Boolean = when (key) {
        "city" -> cityPattern.matches(this) && containsNoHealthSafetyText()
        "budgetMin", "budgetMax" -> decimalPattern.matches(this)
        "downtimeDays" -> wholeNumberPattern.matches(this)
        else -> false
    }

    private fun String.containsNoHealthSafetyText(): Boolean {
        val normalized = lowercase()
        return healthSafetyTerms.none(normalized::contains)
    }

    private companion object {
        const val MAX_OPERANDS = 4
        const val MAX_DIMENSIONS = 5
        const val MAX_CONSTRAINTS = 8
        const val MAX_ENTITY_ID_LENGTH = 100
        const val MAX_DISPLAY_NAME_LENGTH = 120
        const val MAX_CONSTRAINT_KEY_LENGTH = 40
        const val MAX_CONSTRAINT_VALUE_LENGTH = 120
        const val MIN_COMPARISON_SUBJECT_LENGTH = 2

        val allowedDimensions = setOf("PRICE", "CREDENTIALS", "RATING")
        val allowedConstraints = setOf("city", "budgetMin", "budgetMax", "downtimeDays")
        val defaultDimensions = mapOf(
            AgentQueryTarget.INSTITUTION to listOf("CREDENTIALS", "RATING"),
            AgentQueryTarget.DOCTOR to listOf("CREDENTIALS", "RATING"),
            AgentQueryTarget.PROJECT to listOf("PRICE", "RATING"),
            AgentQueryTarget.INSTITUTION_PROJECT to listOf("PRICE", "CREDENTIALS", "RATING")
        )
        val dimensionSignals = listOf(
            "PRICE" to listOf("价格", "报价", "price", "cost"),
            "CREDENTIALS" to listOf("资质", "认证", "credential", "verified"),
            "RATING" to listOf("评分", "评价", "rating", "review")
        )
        val budgetRangePattern = Regex(
            """(?:预算|budget)\D{0,12}(\d{1,9}(?:\.\d{1,2})?)\s*(?:-|~|至|到|to)\s*\D{0,3}(\d{1,9}(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val budgetMinPattern = Regex(
            """(?:预算下限|最低预算|budget\s*min)\D{0,12}(\d{1,9}(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val budgetMaxPattern = Regex(
            """(?:预算上限|最高预算|budget\s*max)\D{0,12}(\d{1,9}(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val downtimePattern = Regex(
            """(?:恢复期|恢复|downtime|recovery)\D{0,12}(\d{1,3})\s*(?:天|days?)""",
            RegexOption.IGNORE_CASE
        )
        val cityPattern = Regex("""[\p{IsHan}A-Za-z][\p{IsHan}A-Za-z .·'-]{0,119}""")
        val decimalPattern = Regex("""\d{1,9}(?:\.\d{1,2})?""")
        val wholeNumberPattern = Regex("""\d{1,3}""")
        val healthSafetyTerms = listOf(
            "怀孕", "孕期", "备孕", "哺乳", "过敏", "瘢痕", "疤痕", "服药", "感染",
            "糖尿病", "高血压", "高血糖", "心脏病", "diabetes", "hypertension", "high blood pressure",
            "pregnant", "pregnancy", "breastfeeding", "allergy", "keloid", "medication", "infection"
        )
        val comparisonActionPattern = Regex(
            "(?:对比|比较|区别|compare|comparison|versus|\\bvs\\b)",
            RegexOption.IGNORE_CASE
        )
        val comparisonTargetPattern = Regex(
            "(?:机构项目|机构套餐|项目套餐|clinic\\s+(?:treatment|package|offering)|institution\\s+project)",
            RegexOption.IGNORE_CASE
        )
        val comparisonSubjectSeparator = Regex("\\s*(?:和|与|及|、|/|\\b(?:and|or)\\b)\\s*", RegexOption.IGNORE_CASE)
    }
}
