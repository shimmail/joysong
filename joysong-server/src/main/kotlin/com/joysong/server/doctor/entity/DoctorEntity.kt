package com.joysong.server.doctor.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "doctors")
@SQLDelete(sql = "UPDATE doctors SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class DoctorEntity(
    /** 医生档案主键即认证用户 ID。 */
    @Id val id: String,
    val name: String,
    val title: String = "",
    val bio: String = "",
    val avatar: String = "",
    @Column(name = "institution_id") val institutionId: String = "",
    @Column(name = "institution_name") val institutionName: String = "",
    val rating: BigDecimal = BigDecimal("4.5"),
    @Column(name = "review_count") val reviewCount: Int = 0,
    val specialties: String = "",
    @Column(name = "is_verified") val isVerified: Boolean = false,
    @Column(name = "consultation_count") val consultationCount: Int = 0,
    val credentials: String = "",
    /** 医生主页公开展示图片；不属于身份认证材料，也不参与认证状态判定。 */
    @Column(name = "credential_images") val credentialImages: String = "",
    @Column(name = "case_count") val caseCount: Int = 0,
    @Column(name = "certification_tags") val certificationTags: String = "",
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
