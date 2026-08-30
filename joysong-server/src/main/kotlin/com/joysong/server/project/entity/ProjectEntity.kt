package com.joysong.server.project.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime
import com.joysong.server.common.money.CurrencyCode

@Entity
@Table(name = "projects")
@SQLDelete(sql = "UPDATE projects SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class ProjectEntity(
    @Id val id: String,
    val name: String,
    val category: String = "",
    val description: String = "",
    val tags: String = "",
    @Column(name = "category_tags") val categoryTags: String = "",
    @Column(name = "cover_image") val coverImage: String = "",
    val images: String = "",
    @Column(name = "reference_price") val referencePrice: BigDecimal = BigDecimal.ZERO,
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    val currency: String = CurrencyCode.DEFAULT_CODE,
    val slogan: String = "",
    @Column(name = "detail_content") val detailContent: String? = null,
    @Column(name = "sales_count") val salesCount: Int = 0,
    val rating: BigDecimal = BigDecimal("4.5"),
    @Column(name = "review_count") val reviewCount: Int = 0,
    @Column(name = "case_count", nullable = false, updatable = false) val caseCount: Int = 0,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
