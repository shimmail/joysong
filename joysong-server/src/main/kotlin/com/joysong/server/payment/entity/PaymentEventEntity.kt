package com.joysong.server.payment.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Lob
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "payment_events")
data class PaymentEventEntity(
    @Id val id: String,
    @Column(name = "payment_id") val paymentId: String? = null,
    @Column(name = "provider", nullable = false, length = 30) val provider: String,
    @Column(name = "provider_event_id", nullable = false, length = 150) val providerEventId: String,
    @Column(name = "event_type", nullable = false, length = 100) val eventType: String,
    @Lob
    @Column(name = "payload", nullable = false, columnDefinition = "LONGTEXT") val payload: String,
    @Column(name = "signature_valid", nullable = false) val signatureValid: Boolean = false,
    @Column(name = "processing_status", nullable = false, length = 30) val processingStatus: String = "RECEIVED",
    @Column(name = "retry_count", nullable = false) val retryCount: Int = 0,
    @Column(name = "error_message", length = 500) val errorMessage: String? = null,
    @Column(name = "received_at", nullable = false) val receivedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "processed_at") val processedAt: LocalDateTime? = null
)
