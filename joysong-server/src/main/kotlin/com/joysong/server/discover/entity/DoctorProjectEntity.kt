package com.joysong.server.discover.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "doctor_projects")
@IdClass(DoctorProjectId::class)
data class DoctorProjectEntity(
    @Id @Column(name = "doctor_id") val doctorId: String = "",
    @Column(name = "project_id") val projectId: String = "",
    @Id @Column(name = "institution_project_id") val institutionProjectId: String = "",
    @Column(nullable = false) val price: BigDecimal,
    @Column(name = "service_description", columnDefinition = "TEXT") val serviceDescription: String = "",
    @Column(name = "service_tags") val serviceTags: String = "",
    @Column(name = "schedule_note") val scheduleNote: String = "",
    @Column(name = "cover_image") val coverImage: String = "",
    val images: String = "",
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null
)

data class DoctorProjectId(
    val doctorId: String = "",
    val institutionProjectId: String = ""
) : java.io.Serializable
