package com.joysong.server.banner.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.time.LocalDateTime

@Entity
@Table(name = "banners")
@SQLDelete(sql = "UPDATE banners SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class BannerEntity(
    @Id val id: String,
    val title: String,
    val subtitle: String = "",
    @Column(name = "image_url") val imageUrl: String = "",
    @Column(name = "accent_color") val accentColor: String = "#E8A0BF",
    @Column(name = "sort_order") val sortOrder: Int = 0,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,
    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
