package com.joysong.server.admin.entity.dto

import java.math.BigDecimal
import com.joysong.server.common.money.CurrencyCode

data class ProjectRequest(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val tags: String = "",
    val categoryTags: String = "",
    val coverImage: String = "",
    val images: String = "",
    val referencePrice: BigDecimal = BigDecimal.ZERO,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val slogan: String = "",
    val detailContent: String? = null,
    val rating: BigDecimal = BigDecimal("4.5"),
    val reviewCount: Int = 0,
    val salesCount: Int = 0,
    val doctorIds: List<String> = emptyList(),
    val doctorBindings: List<DoctorProjectBinding> = emptyList()
)
