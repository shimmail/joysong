package com.joysong.server.agent.service

import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
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
