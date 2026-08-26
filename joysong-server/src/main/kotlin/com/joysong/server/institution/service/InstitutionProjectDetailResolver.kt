package com.joysong.server.institution.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import org.springframework.stereotype.Component

/**
 * 机构项目详情的唯一继承入口。机构项目未配置的字段使用公共项目模板值。
 */
@Component
class InstitutionProjectDetailResolver(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) {
    fun resolve(ip: InstitutionProjectEntity, project: ProjectEntity): ProjectEntity = project.copy(
        name = ip.name.inherit(project.name),
        category = ip.category.inherit(project.category),
        description = ip.description.inherit(project.description),
        rating = ip.rating ?: project.rating,
        reviewCount = ip.reviewCount ?: project.reviewCount,
        tags = ip.tags.inherit(project.tags),
        slogan = ip.slogan.inherit(project.slogan),
        detailContent = ip.detailContent.inheritNullable(project.detailContent),
        coverImage = ip.coverImage?.takeIf { it.isNotBlank() } ?: project.coverImage,
        images = ip.images?.takeIf { it.isNotBlank() } ?: project.images,
        salesCount = ip.salesCount
    )

    fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun normalizeRichText(value: String?): String? = normalize(value)?.takeIf { html ->
        val plainText = html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", "")
            .replace("&#160;", "")
        plainText.isNotBlank() || html.contains("<img", ignoreCase = true) || html.contains("<video", ignoreCase = true)
    }

    fun resolveDetails(ip: InstitutionProjectEntity, project: ProjectEntity): InstitutionProjectDetailResolution {
        val effectiveProject = resolve(ip, project)
        return InstitutionProjectDetailResolution(
            rawOverrides = ProjectRawOverridesSnapshot(
                name = normalize(ip.name),
                category = normalize(ip.category),
                description = normalize(ip.description),
                tags = decodeOptionalList(ip.tags),
                slogan = normalize(ip.slogan),
                detailContent = normalize(ip.detailContent),
                coverImage = normalize(ip.coverImage),
                images = decodeOptionalList(ip.images)
            ),
            effective = ProjectEffectiveSnapshot(
                name = effectiveProject.name,
                category = effectiveProject.category,
                description = normalize(effectiveProject.description),
                tags = decodeList(effectiveProject.tags),
                slogan = normalize(effectiveProject.slogan),
                detailContent = normalize(effectiveProject.detailContent),
                salesCount = effectiveProject.salesCount,
                coverImage = normalize(effectiveProject.coverImage),
                images = decodeList(effectiveProject.images)
            )
        )
    }

    private fun decodeOptionalList(value: String?): List<String>? = normalize(value)
        ?.let(::decodeList)
        ?.takeIf(List<String>::isNotEmpty)

    private fun decodeList(value: String?): List<String> {
        val normalized = normalize(value) ?: return emptyList()
        val values = if (normalized.startsWith("[")) {
            objectMapper.readValue(normalized, object : TypeReference<List<String>>() {})
        } else {
            normalized.split(',')
        }
        return values.mapNotNull { normalize(it) }
    }

    private fun String?.inherit(fallback: String): String = normalize(this) ?: fallback

    private fun String?.inheritNullable(fallback: String?): String? = normalize(this) ?: fallback
}
