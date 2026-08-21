package com.joysong.server.order.dto

data class CreateOrderRequest(
    val projectId: String,
    val institutionProjectId: String? = null,
    val doctorId: String = "",
    val consultantId: String = "",
    val remark: String = "",
    /** 预约时间（ISO-8601 格式，如 2026-08-01T10:00:00） */
    val appointmentTime: String? = null
)
