package com.joysong.server.order.service

import com.joysong.server.notification.service.BusinessNotificationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class OrderBusinessNotificationDispatcher(
    private val businessNotificationService: BusinessNotificationService
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderCancelled(orderId: String, userId: String, consultantId: String) {
        businessNotificationService.orderCancelled(orderId, userId, consultantId)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun orderRefunded(orderId: String, userId: String, consultantId: String, doctorId: String) {
        businessNotificationService.orderRefunded(orderId, userId, consultantId, doctorId)
    }
}
