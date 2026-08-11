package com.joysong.server.agent.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.chat.entity.ChatMessageEntity
import java.util.Locale

object PlanningCatalogProjection {
    private val priceLabels = setOf(
        "参考价", "机构价格", "项目参考价",
        "reference price", "clinic price", "project reference price"
    )
    private val numericLabels = setOf(
        "评分", "评价数", "评价量", "销量", "医生数", "项目数",
        "rating", "reviews", "review count", "sales", "doctors", "projects"
    )
    private val verificationLabels = setOf(
        "认证", "机构认证", "verified", "clinic verified"
    )
    private val safeModes = setOf("SUMMARY", "COMPARISON")
    private val verificationValues = setOf(
        "已认证", "未认证", "verified", "not verified", "true", "false"
    )
    private val pricePunctuation = setOf('.', ',', '$', '¥', '￥', '€', '£', '-', '~', '–', '—')

    fun safeContent(): String = AgentText.value(
        "以下仅作为平台信息参考，不构成诊断或治疗建议。平台可展示相关项目名称、价格等结构化资料；个人适用性、恢复期、疼痛程度、禁忌与风险需向机构确认，必要时由具备资质的医生面诊确认。",
        "This is platform information reference only and is not diagnosis or treatment advice. The platform can show structured details such as names and prices; personal suitability, downtime, pain, contraindications, and risks require confirmation with the institution or a qualified clinician."
    )

    fun projectStoredMessage(message: ChatMessageEntity, objectMapper: ObjectMapper): ChatMessageEntity {
        if (!message.role.trim().equals("ASSISTANT", ignoreCase = true)) return message
        val metadata = runCatching { objectMapper.readTree(message.metadataJson.ifBlank { "{}" }) }.getOrNull()
            ?: return message
        if (!metadata.path("intent").asText().trim().equals("PLANNING", ignoreCase = true)) return message
        val items = metadata.path("catalogItems").takeIf { it.isArray }?.mapNotNull { item ->
            runCatching { objectMapper.treeToValue(item, AgentCatalogItemResponse::class.java) }.getOrNull()
        }.orEmpty()
        val report = metadata.get("catalogReport")?.takeUnless { it.isNull }?.let { value ->
            runCatching { objectMapper.treeToValue(value, AgentCatalogReportResponse::class.java) }.getOrNull()
        }
        val projectedMetadata = objectMapper.writeValueAsString(
            linkedMapOf(
                "intent" to "PLANNING",
                "queryTarget" to metadata.get("queryTarget")?.takeUnless { it.isNull }?.asText(),
                "nextAction" to metadata.path("nextAction").asText("NONE"),
                "catalogItems" to projectItems(items),
                "catalogReport" to projectReport(report)
            )
        )
        return message.copy(content = safeContent(), metadataJson = projectedMetadata)
    }

    fun projectItems(items: List<AgentCatalogItemResponse>): List<AgentCatalogItemResponse> =
        items.map { item ->
            item.copy(
                subtitle = "",
                summary = "",
                attributes = projectAttributes(item.attributes)
            )
        }

    fun projectReport(report: AgentCatalogReportResponse?): AgentCatalogReportResponse? = report?.let {
        AgentCatalogReportResponse(
            mode = it.mode.trim().uppercase(Locale.ROOT).takeIf(safeModes::contains) ?: "SUMMARY",
            title = AgentText.value("平台信息参考", "Platform information reference"),
            summary = AgentText.value(
                "仅展示平台结构化资料；医疗相关信息需向机构确认。",
                "Only structured platform data is shown; confirm medical details with the institution."
            ),
            items = projectItems(it.items),
            comparisonDimensions = emptyList(),
            warnings = listOf(
                AgentText.value("不构成诊断或治疗建议。", "This is not diagnosis or treatment advice.")
            )
        )
    }

    private fun projectAttributes(attributes: Map<String, String>): Map<String, String> =
        linkedMapOf<String, String>().apply {
            attributes.forEach { (label, rawValue) ->
                val value = rawValue.trim()
                if (isSafeStructuredValue(label, value)) put(label, value)
            }
        }

    private fun isSafeStructuredValue(label: String, value: String): Boolean {
        if (value.isEmpty() || value.length > 32) return false
        val normalizedLabel = label.trim().lowercase(Locale.ROOT)
        return when (normalizedLabel) {
            in priceLabels -> value.any(Char::isDigit) && value.all {
                it.isDigit() || it.isWhitespace() || it in pricePunctuation
            }
            in numericLabels -> value.any(Char::isDigit) && value.all {
                it.isDigit() || it == '.' || it == ','
            }
            in verificationLabels -> value.lowercase(Locale.ROOT) in verificationValues
            else -> false
        }
    }
}
