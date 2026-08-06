package com.joysong.server.admin.entity.vo

import java.math.BigDecimal
import java.time.LocalDateTime

data class ProjectAdminVo(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val tags: String,
    val categoryTags: String,
    val coverImage: String,
    val images: String,
    val referencePrice: BigDecimal,
    val slogan: String,
    val detailContent: String?,
    val rating: BigDecimal,
    val reviewCount: Int,
    val salesCount: Int,
    val createdAt: LocalDateTime,
    val doctorIds: List<String>
)
