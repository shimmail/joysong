package com.joysong.app.domain.model

data class Institution(
    val id: String,
    val name: String,
    val address: String = "",
    val city: String = "",
    val description: String = "",
    val coverImage: String = "",
    val rating: Float = 0f,
    val reviewCount: Int = 0,
    val isVerified: Boolean = false,
    val images: List<String> = emptyList(),
    val projectCount: Int = 0,
    val doctorCount: Int = 0,
    val consultationCount: Int = 0,
    val credentials: String = "",
    val credentialImages: String = "",
    val specialties: String = "",
    val establishedYear: Int? = null,
    val certificationTime: String = "",
    val userCount: Int = 0,
    val tags: String = "",
    val contactPhone: String = "",
    val businessHours: String = "",
    val caseCount: Int = 0
)
