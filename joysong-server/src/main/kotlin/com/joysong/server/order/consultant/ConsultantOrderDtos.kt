package com.joysong.server.order.consultant

import java.time.LocalDateTime

data class ConsultantOrderPageResponse(
    val items: List<ConsultantOrderSummaryResponse>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

data class ConsultantOrderSummaryResponse(
    val id: String,
    val orderNo: String,
    val stage: String,
    val status: String,
    val refundStatus: String,
    val project: ConsultantOrderProjectResponse,
    val institution: ConsultantOrderInstitutionResponse,
    val customer: ConsultantOrderCustomerResponse,
    val appointmentTime: LocalDateTime?,
    val updatedAt: LocalDateTime,
    val conversationReadable: Boolean,
    val messageSendable: Boolean,
    val readOnly: Boolean
)

data class ConsultantOrderDetailResponse(
    val id: String,
    val orderNo: String,
    val stage: String,
    val status: String,
    val refundStatus: String,
    val project: ConsultantOrderProjectResponse,
    val institution: ConsultantOrderInstitutionResponse,
    val customer: ConsultantOrderCustomerResponse,
    val doctor: ConsultantOrderDoctorResponse,
    val appointmentTime: LocalDateTime?,
    val remark: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val serviceActivatedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val conversationReadable: Boolean,
    val messageSendable: Boolean,
    val readOnly: Boolean,
    val conversation: ConsultantOrderConversationResponse
)

data class ConsultantOrderProjectResponse(val id: String, val name: String, val coverImage: String)

data class ConsultantOrderInstitutionResponse(val id: String, val name: String)

data class ConsultantOrderCustomerResponse(val displayName: String, val avatar: String?)

data class ConsultantOrderDoctorResponse(val id: String?, val name: String)

data class ConsultantOrderConversationResponse(val readable: Boolean, val sendable: Boolean)
