package com.joysong.server.payment.service

import com.joysong.server.notification.service.BusinessNotificationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentBusinessNotificationDispatcher(
    private val businessNotificationService: BusinessNotificationService
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderServiceActivated(orderId: String, userId: String, consultantId: String, doctorId: String) {
        businessNotificationService.orderServiceActivated(orderId, userId, consultantId, doctorId)
    }
}
