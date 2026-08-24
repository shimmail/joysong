package com.joysong.server.project.service

import org.springframework.stereotype.Component
import java.text.Normalizer

data class InstitutionProjectPayload(
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val coverImage: String? = null,
    val images: List<String>? = null,
    val salesCount: Int = 0
)

@Component
class InstitutionProjectPayloadPolicy {
    fun normalize(payload: InstitutionProjectPayload): InstitutionProjectPayload {
        require(payload.salesCount >= 0) { "销量不能为负数" }
        return InstitutionProjectPayload(
            name = optionalText("项目名称", payload.name, 200),
            category = optionalText("项目分类", payload.category, 100),
            description = optionalText("服务内容", payload.description, 5_000),
            tags = optionalList("项目标签", payload.tags, 20, 100, 500),
            slogan = optionalText("项目标语", payload.slogan, 500),
            detailContent = optionalText("项目详情", payload.detailContent, 20_000),
            coverImage = optionalText("封面图片", payload.coverImage, 500),
            images = optionalList("项目图片", payload.images, 20, 500, 2_000),
            salesCount = payload.salesCount
        )
    }

    private fun optionalText(label: String, value: String?, maxLength: Int): String? = value
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        ?.also { require(it.length <= maxLength) { "${label}不能超过 $maxLength 个字符" } }

    private fun optionalList(
        label: String,
        values: List<String>?,
        maxItems: Int,
        maxItemLength: Int,
        maxTargetLength: Int
    ): List<String>? {
        if (values.isNullOrEmpty()) return null
        require(values.size <= maxItems) { "${label}最多包含 $maxItems 项" }
        val normalized = values.mapNotNull { value ->
            value.trim().takeIf(String::isNotEmpty)?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        }
        if (normalized.isEmpty()) return null
        normalized.forEach { require(it.length <= maxItemLength) { "${label}每项不能超过 $maxItemLength 个字符" } }
        require(normalized.joinToString(",").length <= maxTargetLength) {
            "${label}不能超过 $maxTargetLength 个字符"
        }
        return normalized
    }
}
