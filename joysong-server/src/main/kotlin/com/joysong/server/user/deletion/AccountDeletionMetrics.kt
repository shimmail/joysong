package com.joysong.server.user.deletion

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class AccountDeletionMetrics(
    private val registry: MeterRegistry,
) {
    fun count(event: String, errorCode: String = "NONE") {
        registry.counter("account.deletion.events", "event", event, "errorCode", errorCode).increment()
    }

    fun startTransaction(): Timer.Sample = Timer.start(registry)

    fun stopTransaction(sample: Timer.Sample, outcome: String) {
        sample.stop(
            Timer.builder("account.deletion.transaction.duration")
                .tag("outcome", outcome)
                .register(registry),
        )
    }
}
