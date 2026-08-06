package com.joysong.server.institution.service

import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import org.springframework.stereotype.Component

/**
 * 机构项目详情的唯一继承入口。机构项目未配置的字段使用公共项目模板值。
 */
@Component
class InstitutionProjectDetailResolver {
    fun resolve(ip: InstitutionProjectEntity, project: ProjectEntity): ProjectEntity = project.copy(
        name = ip.name.inherit(project.name),
        category = ip.category.inherit(project.category),
        description = ip.description.inherit(project.description),
        rating = ip.rating ?: project.rating,
        reviewCount = ip.reviewCount ?: project.reviewCount,
        tags = ip.tags.inherit(project.tags),
        slogan = ip.slogan.inherit(project.slogan),
        detailContent = ip.detailContent.inheritNullable(project.detailContent),
        coverImage = ip.coverImage.ifBlank { project.coverImage },
        images = ip.images.ifBlank { project.images },
        salesCount = ip.salesCount
    )

    fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun normalizeRichText(value: String?): String? = normalize(value)?.takeIf { html ->
        val plainText = html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", "")
            .replace("&#160;", "")
        plainText.isNotBlank() || html.contains("<img", ignoreCase = true) || html.contains("<video", ignoreCase = true)
    }

    private fun String?.inherit(fallback: String): String = normalize(this) ?: fallback

    private fun String?.inheritNullable(fallback: String?): String? = normalize(this) ?: fallback
}
