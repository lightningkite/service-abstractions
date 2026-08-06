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
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [OtelTelemetryBackend.reportError] directly (the JVM telemetry path that used to live in
 * basis as `reportExceptionToTelemetry`). Covers both branches: recording onto an active span, and
 * emitting a standalone ERROR log record when no span is current.
 */
class OtelTelemetryBackendReportErrorTest {

    @Test
    fun recordsOnActiveSpan() {
        val spans = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
        val sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val backend = OtelTelemetryBackend(sdk)

        val span = sdk.getTracer("test").spanBuilder("op").startSpan()
        span.makeCurrent().use {
            backend.reportError(
                RuntimeException("boom"),
                TelemetryAttributes { put(TelemetryKey.OfString("operation"), "createIndex") },
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
        val backend = OtelTelemetryBackend(sdk)

        // No active span: this must emit a standalone ERROR log record.
        backend.reportError(
            IllegalStateException("offline failure"),
            TelemetryAttributes { put(TelemetryKey.OfString("table"), "users") },
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

    /** A connection-failure message shaped like what a real driver produces: credentials embedded in a URL. */
    private val credentialLeakingMessage = "Failed to connect to mongodb://admin:S3cr3t@db.internal:27017/mydb"

    private fun assertNoCredentials(value: String?) {
        assertNotNull(value)
        assertFalse(value.contains("admin"), "leaked username: $value")
        assertFalse(value.contains("S3cr3t"), "leaked password: $value")
        assertTrue(value.contains("db.internal:27017/mydb"), "over-redacted, lost the host: $value")
    }

    @Test
    fun recordsOnActiveSpan_sanitizesCredentialsInExceptionMessage() {
        val spans = InMemorySpanExporter.create()
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build()
        val sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build()
        val backend = OtelTelemetryBackend(sdk)

        val span = sdk.getTracer("test").spanBuilder("op").startSpan()
        span.makeCurrent().use {
            backend.reportError(RuntimeException(credentialLeakingMessage), TelemetryAttributes {})
        }
        span.end()

        val data = spans.finishedSpanItems.single()
        // Status description is derived from the exception message.
        assertNoCredentials(data.status.description)

        val exceptionEvent = data.events.single { it.name == "exception" }
        val eventAttrs = exceptionEvent.attributes.asMap().mapKeys { it.key.key }
        assertNoCredentials(eventAttrs["exception.message"] as? String)
        // stackTraceToString() embeds the exception message on its first line too.
        assertNoCredentials(eventAttrs["exception.stacktrace"] as? String)
    }

    @Test
    fun emitsLogRecordWhenNoSpan_sanitizesCredentialsInExceptionMessage() {
        val logs = InMemoryLogRecordExporter.create()
        val loggerProvider = SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(logs))
            .build()
        val sdk = OpenTelemetrySdk.builder().setLoggerProvider(loggerProvider).build()
        val backend = OtelTelemetryBackend(sdk)

        backend.reportError(IllegalStateException(credentialLeakingMessage), TelemetryAttributes {})

        val record = logs.finishedLogRecordItems.single()
        @Suppress("DEPRECATION")
        assertNoCredentials(record.body.asString())
        val attrs = record.attributes.asMap().mapKeys { it.key.key }
        assertNoCredentials(attrs["exception.message"] as? String)
        assertNoCredentials(attrs["exception.stacktrace"] as? String)
    }
}
