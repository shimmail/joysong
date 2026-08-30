package com.joysong.server.institution.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime
import com.joysong.server.common.money.CurrencyCode

@Entity
@Table(name = "institution_projects")
@SQLDelete(
    sql = "UPDATE institution_projects " +
        "SET deleted_at = NOW(), version = version + 1 " +
        "WHERE id = ? AND version = ?"
)
@Where(clause = "deleted_at IS NULL")
data class InstitutionProjectEntity(
    @Id
    @Column(name = "id")
    val id: String = "",

    @Column(name = "institution_id", nullable = false)
    val institutionId: String = "",

    @Column(name = "project_id", nullable = false)
    val projectId: String = "",

    @Column(name = "name", length = 200)
    val name: String? = null,

    @Column(name = "category", length = 100)
    val category: String? = null,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String? = null,

    @Column(name = "rating", precision = 2, scale = 1)
    val rating: BigDecimal? = null,

    @Column(name = "review_count")
    val reviewCount: Int? = null,

    @Column(name = "case_count", nullable = false, updatable = false)
    val caseCount: Int = 0,

    @Column(name = "tags", length = 500)
    val tags: String? = null,

    @Column(name = "slogan", length = 500)
    val slogan: String? = null,

    @Column(name = "detail_content", columnDefinition = "TEXT")
    val detailContent: String? = null,

    @Column(name = "price", nullable = false)
    val price: BigDecimal = BigDecimal.ZERO,

    @Column(name = "original_price", nullable = true)
    val originalPrice: BigDecimal? = null,

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    val currency: String = CurrencyCode.DEFAULT_CODE,

    @Column(name = "cover_image")
    val coverImage: String? = null,

    @Column(name = "images")
    val images: String? = null,

    @Column(name = "sales_count")
    val salesCount: Int = 0,

    @Column(name = "is_active")
    val isActive: Boolean = true,

    @Version
    @Column(name = "version", nullable = false)
    val version: Long = 0,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    val updatedAt: LocalDateTime? = null,

    @Column(name = "deleted_at")
    val deletedAt: LocalDateTime? = null
)
