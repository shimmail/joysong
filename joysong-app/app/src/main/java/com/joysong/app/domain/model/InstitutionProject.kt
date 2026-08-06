package com.joysong.app.domain.model

data class InstitutionProject(
    val id: String = "",
    val institutionId: String = "",
    val projectId: String = "",
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: List<String> = emptyList(),
    val slogan: String = "",
    val detailContent: String? = null,
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val salesCount: Int = 0,
    val isActive: Boolean = true
)
