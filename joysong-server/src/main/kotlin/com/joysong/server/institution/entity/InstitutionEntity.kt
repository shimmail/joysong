package com.joysong.server.institution.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "institutions")
@SQLDelete(sql = "UPDATE institutions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class InstitutionEntity(
    @Id val id: String,
    val name: String,
    val address: String = "",
    val city: String = "",
    val description: String = "",
    @Column(name = "cover_image") val coverImage: String = "",
    val rating: BigDecimal = BigDecimal.ZERO,
    @Column(name = "review_count") val reviewCount: Int = 0,
    @Column(name = "is_verified") val isVerified: Boolean = false,
    val images: String = "",
    @Column(name = "project_count") val projectCount: Int = 0,
    @Column(name = "doctor_count") val doctorCount: Int = 0,
    @Column(name = "consultation_count") val consultationCount: Int = 0,
    val credentials: String = "",
    @Column(name = "established_year") val establishedYear: Int? = null,
    @Column(name = "certification_time") val certificationTime: LocalDate? = null,
    @Column(name = "user_count") val userCount: Int = 0,
    @Column(name = "credential_images") val credentialImages: String = "",
    val specialties: String = "",
    val tags: String = "",
    @Column(name = "contact_phone") val contactPhone: String = "",
    @Column(name = "business_hours") val businessHours: String = "",
    @Column(name = "case_count") val caseCount: Int = 0,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
