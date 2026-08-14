package com.joysong.server.doctor.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDateTime

@Entity
@Table(name = "doctor_institutions")
@SQLDelete(sql = "UPDATE doctor_institutions SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class DoctorInstitutionEntity(
    @Id val id: String,
    @Column(name = "doctor_id") val doctorId: String,
    @Column(name = "institution_id") val institutionId: String,
    @Column(name = "is_primary") val isPrimary: Boolean = false,
    val status: String = "APPROVED",
    @Column(name = "registration_no") val registrationNo: String = "",
    @Column(name = "registration_file_id") val registrationFileId: String? = null,
    @Column(name = "confirmed_by") val confirmedBy: String? = null,
    @Column(name = "confirmed_at") val confirmedAt: LocalDateTime? = null,
    @Column(name = "revoked_at") val revokedAt: LocalDateTime? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    // Hibernate 会显式插入该列；不能依赖数据库默认值，否则严格模式下会写入 NULL。
    @Column(name = "updated_at") val updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
