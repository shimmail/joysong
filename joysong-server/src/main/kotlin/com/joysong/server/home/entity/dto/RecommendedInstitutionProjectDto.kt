package com.joysong.server.home.entity.dto

import java.math.BigDecimal

/**
 * 首页推荐机构项目 DTO
 */
data class RecommendedInstitutionProjectDto(
    val institutionProjectId: String,
    val institutionId: String,
    val projectId: String,
    val projectName: String,
    val institutionName: String,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val currency: String = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,
    val coverImage: String,
    val category: String,
    val salesCount: Int,
    val caseCount: Int = 0,
    val description: String,
    val rating: BigDecimal,
    val reviewCount: Int,
    val tags: String,
    val slogan: String,
    val detailContent: String?
)
