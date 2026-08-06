package com.joysong.app.domain.model

/**
 * 机构详情页中的可预约项目信息（合并了机构项目关联信息与项目模板信息）
 * - price/originalPrice: 机构特定价格（非参考价）
 * - coverImage: 机构项目封面，若无则回退到项目模板封面
 * - category/categoryTags: 用于筛选过滤
 */
data class InstitutionProjectInfo(
    val institutionProjectId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val description: String = "",
    val category: String = "",
    val categoryTags: List<String> = emptyList(),
    val salesCount: Int = 0,
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: List<String> = emptyList(),
    val slogan: String = "",
    val detailContent: String? = null,
    val images: List<String> = emptyList()
)
