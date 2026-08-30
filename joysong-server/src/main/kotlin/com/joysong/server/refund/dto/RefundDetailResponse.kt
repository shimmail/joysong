package com.joysong.server.refund.dto

import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.joysong.server.refund.entity.RefundEntity

data class RefundDetailResponse(
    @get:JsonUnwrapped val refund: RefundEntity,
    val evidenceFiles: List<RefundEvidenceFileResponse>,
)
