package com.joysong.server.payment.repository

import com.joysong.server.payment.entity.PaymentEventEntity
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentEventRepository : JpaRepository<PaymentEventEntity, String> {
    fun findByProviderAndProviderEventId(provider: String, providerEventId: String): PaymentEventEntity?
    fun findTop50ByProcessingStatusAndRetryCountLessThanOrderByReceivedAtAsc(
        processingStatus: String,
        retryCount: Int
    ): List<PaymentEventEntity>
}
