package com.joysong.server.refund.dto

data class RefundEvidenceFileResponse(
    val fileId: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val position: Int,
)
