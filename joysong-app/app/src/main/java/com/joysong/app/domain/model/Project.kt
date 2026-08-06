package com.joysong.app.domain.model

data class Project(
    val id: String,
    val name: String,
    val referencePrice: Double = 0.0,
    val slogan: String = "",
    val detailContent: String? = null,
    val coverImage: String = "",
    val category: String = "",
    val description: String = "",
    val rating: Float = 4.5f,
    val reviewCount: Int = 0,
    val tags: List<String> = emptyList(),
    val images: List<String> = emptyList(),
    val salesCount: Int = 0,
    val categoryTags: List<String> = emptyList()
)
