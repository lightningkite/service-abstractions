package com.lightningkite.services.otel

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

internal object PrintSpanExporter : SpanExporter {
    private val isShutdown = AtomicBoolean()

    var overhead = 0L

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode? {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        val start = System.nanoTime()
        for(span in spans) {
            println("${span.name} (${span.traceId}/${span.spanId}) took ${span.endEpochNanos - span.startEpochNanos} nanoseconds")
            span.events.forEach {
                println("  ${it.name} (${it.epochNanos - span.startEpochNanos} nanoseconds)")
            }
        }
        overhead += System.nanoTime() - start
        return CompletableResultCode.ofSuccess()
    }

    /**
     * Flushes the data.
     *
     * @return the result of the operation
     */
    override fun flush(): CompletableResultCode {
        val resultCode = CompletableResultCode()
        return resultCode.succeed()
    }

    override fun shutdown(): CompletableResultCode? {
        if (!isShutdown.compareAndSet(false, true)) {
            println("Calling shutdown() multiple times.")
            return CompletableResultCode.ofSuccess()
        }
        return flush()
    }

    override fun toString(): String {
        return "LoggingSpanExporter{}"
    }
}

internal object PrintMetricExporter : MetricExporter {
    val preferredTemporality: AggregationTemporality = AggregationTemporality.DELTA
    private val isShutdown = AtomicBoolean()

    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality {
        return this.preferredTemporality
    }

    override fun export(metrics: MutableCollection<MetricData?>): CompletableResultCode? {
        if (isShutdown.get()) {
            return CompletableResultCode.ofFailure()
        }

        println("Received a collection of " + metrics.size + " metrics for export.")
        for (metricData in metrics) {
            println("metric: {$metricData}")
        }
        return CompletableResultCode.ofSuccess()
    }

    /**
     * Flushes the data.
     *
     * @return the result of the operation
     */
    override fun flush(): CompletableResultCode {
        val resultCode = CompletableResultCode()
        return resultCode.succeed()
    }

    override fun shutdown(): CompletableResultCode? {
        if (!isShutdown.compareAndSet(false, true)) {
            println("Calling shutdown() multiple times.")
            return CompletableResultCode.ofSuccess()
        }
        return flush()
    }

    override fun toString(): String {
        return "LoggingMetricExporter{}"
    }
}

internal object PrintLogExporter: LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        println("Got logs: ${logs.size}")
        logs.forEach { it -> println("LOG (${it.spanContext.traceId}/${it.spanContext.spanId}): ${it.bodyValue?.value}") }
        return CompletableResultCode.ofSuccess()
    }
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}