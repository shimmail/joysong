package com.joysong.server.refund.service

import com.joysong.server.notification.service.BusinessNotificationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class RefundBusinessNotificationDispatcher(
    private val businessNotificationService: BusinessNotificationService
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderRefundRequested(orderId: String, userId: String, consultantId: String, doctorId: String) {
        businessNotificationService.orderRefundRequested(orderId, userId, consultantId, doctorId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderRefundApproved(orderId: String, userId: String, consultantId: String, doctorId: String) {
        businessNotificationService.orderRefundApproved(orderId, userId, consultantId, doctorId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderRefundRejected(
        orderId: String,
        userId: String,
        consultantId: String,
        doctorId: String,
        rejectReason: String
    ) {
        businessNotificationService.orderRefundRejected(orderId, userId, consultantId, doctorId, rejectReason)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderRefunded(orderId: String, userId: String, consultantId: String, doctorId: String) {
        businessNotificationService.orderRefunded(orderId, userId, consultantId, doctorId)
    }
}
