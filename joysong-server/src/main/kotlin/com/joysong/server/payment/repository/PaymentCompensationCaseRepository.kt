package com.joysong.server.payment.repository

import com.joysong.server.payment.entity.PaymentCompensationCaseEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PaymentCompensationCaseRepository : JpaRepository<PaymentCompensationCaseEntity, String> {
    fun findByPaymentId(paymentId: String): PaymentCompensationCaseEntity?
    fun findAllByOrderByCreatedAtDesc(): List<PaymentCompensationCaseEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PaymentCompensationCaseEntity c WHERE c.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): PaymentCompensationCaseEntity?
}
