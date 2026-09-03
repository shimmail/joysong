package com.joysong.server.common.service

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class PublicUploadMetrics(
    private val registry: MeterRegistry,
) {
    fun recordPhase(phase: UploadMetricPhase, durationNanos: Long) {
        Timer.builder("public.upload.phase.duration")
            .tag("phase", phase.wireValue)
            .register(registry)
            .record(Duration.ofNanos(durationNanos.coerceAtLeast(0)))
    }

    fun recordRequest(outcome: UploadMetricOutcome, durationNanos: Long, contentLength: Long?) {
        Timer.builder("public.upload.request.duration")
            .tag("outcome", outcome.wireValue)
            .register(registry)
            .record(Duration.ofNanos(durationNanos.coerceAtLeast(0)))
        contentLength?.let {
            DistributionSummary.builder("public.upload.request.bytes")
                .tag("outcome", outcome.wireValue)
                .baseUnit("bytes")
                .register(registry)
                .record(it.toDouble())
        }
    }
}

enum class UploadMetricPhase(val wireValue: String) {
    MULTIPART("multipart"),
    STAGE("stage"),
    STORAGE("storage"),
    REGISTRY("registry"),
}

enum class UploadMetricOutcome(val wireValue: String) {
    LEGACY_COMPLETE("legacy_complete"),
    COMPLETE("complete"),
    REPLAYED("replayed"),
    PENDING("pending"),
    CONFLICT("conflict"),
    FAILED("failed"),
}
