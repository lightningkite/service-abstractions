package com.lightningkite.services.otel

import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import com.lightningkite.services.MetricAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Exercises [OtelMetricsBackend.reportError] directly (the JVM telemetry path that used to live in
 * basis as `reportExceptionToTelemetry`). Covers both branches: recording onto an active span, and
 * emitting a standalone ERROR log record when no span is current.
 */
class OtelMetricsBackendReportErrorTest {

    @Test
    fun recordsOnActiveSpan() {
        val spans = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
        val sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val backend = OtelMetricsBackend(sdk)

        val span = sdk.getTracer("test").spanBuilder("op").startSpan()
        span.makeCurrent().use {
            backend.reportError(
                RuntimeException("boom"),
                MetricAttributes(mapOf("operation" to "createIndex")),
            )
        }
        span.end()

        val data = spans.finishedSpanItems.single()
        assertEquals(StatusCode.ERROR, data.status.statusCode)
        assertEquals(1, data.events.count { it.name == "exception" })
        assertNotNull(data.attributes.asMap().entries.find { it.key.key == "error.fingerprint" })
        assertEquals("createIndex", data.attributes.asMap().entries.find { it.key.key == "operation" }?.value)
    }

    @Test
    fun emitsLogRecordWhenNoSpan() {
        val logs = InMemoryLogRecordExporter.create()
        val loggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
            .build()
        val sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build()
        val backend = OtelMetricsBackend(sdk)

        // No active span: this must emit a standalone ERROR log record.
        backend.reportError(
            IllegalStateException("offline failure"),
            MetricAttributes(mapOf("table" to "users")),
        )

        val record = logs.finishedLogRecordItems.single()
        assertEquals(Severity.ERROR, record.severity)
        @Suppress("DEPRECATION")
        assertEquals("offline failure", record.body.asString())
        val attrs = record.attributes.asMap().mapKeys { it.key.key }
        assertNotNull(attrs["error.fingerprint"])
        assertEquals("java.lang.IllegalStateException", attrs["exception.type"])
        assertEquals("users", attrs["table"])
    }
}
