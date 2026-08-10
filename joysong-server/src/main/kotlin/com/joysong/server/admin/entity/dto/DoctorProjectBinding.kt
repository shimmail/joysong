package com.joysong.server.admin.entity.dto

import java.math.BigDecimal

/**
 * 医生-项目绑定关系，用于后台管理指定医生与机构项目的关联
 */
data class DoctorProjectBinding(
    val doctorId: String,
    val institutionProjectId: String = "",
    val price: BigDecimal? = null
)
