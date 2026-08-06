package com.joysong.app.domain.model

data class DoctorInstitutionProjectInfo(
    val institutionProjectId: String = "",
    val projectId: String = "",
    val projectName: String = "",
    val institutionId: String = "",
    val institutionName: String = "",
    val price: Double = 0.0,
    val originalPrice: Double? = null,
    val coverImage: String = "",
    val salesCount: Int = 0,
    val category: String = "",
    val description: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val tags: List<String> = emptyList(),
    val slogan: String = "",
    val detailContent: String? = null
)
