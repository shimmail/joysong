package com.joysong.app.domain.model

data class Doctor(
    val id: String,
    val name: String,
    val title: String = "",
    val bio: String = "",
    val avatar: String = "",
    val institutionId: String = "",
    val institutionName: String = "",
    val rating: Float = 4.5f,
    val reviewCount: Int = 0,
    val specialties: List<String> = emptyList(),
    val isVerified: Boolean = false,
    val consultationCount: Int = 0,
    val credentials: String = "",
    val credentialImages: String = "",
    val caseCount: Int = 0,
    val certificationTags: List<String> = emptyList()
)
