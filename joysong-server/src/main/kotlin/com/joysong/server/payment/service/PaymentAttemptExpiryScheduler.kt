package com.joysong.server.payment.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class PaymentAttemptExpiryScheduler(
    private val paymentAttemptExpiryService: PaymentAttemptExpiryService
) {
    companion object {
        private val log = LoggerFactory.getLogger(PaymentAttemptExpiryScheduler::class.java)
    }

    @Scheduled(fixedDelay = 60_000)
    fun expireDueAttempts() {
        val count = paymentAttemptExpiryService.expireDueAttempts(LocalDateTime.now())
        if (count > 0) log.info("本次共关闭{}条已过期支付尝试", count)
    }
}
