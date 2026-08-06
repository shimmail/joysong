package com.joysong.app.domain.model

/**
 * 首页推荐机构项目（合并了机构项目关联信息与项目模板信息）
 */
data class RecommendedInstitutionProject(
    val institutionProjectId: String = "",
    val institutionId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val institutionName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val category: String = "",
    val salesCount: Int = 0,
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: List<String> = emptyList(),
    val slogan: String = "",
    val detailContent: String? = null
)
