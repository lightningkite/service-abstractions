package com.lightningkite.services.otel

import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.telemetry.LogLevel
import com.lightningkite.services.telemetry.MetricUnit
import com.lightningkite.services.telemetry.RecordedError
import com.lightningkite.services.telemetry.RecordedLog
import com.lightningkite.services.telemetry.RecordedMetric
import com.lightningkite.services.telemetry.RecordedSpan
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBatch
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.TraceParent
import com.lightningkite.services.telemetry.telemetryAttributesOf
import com.lightningkite.services.telemetry.telemetryTrace
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class OtelTelemetryBackendIngestTest {
    private val spans = InMemorySpanExporter.create()
    private val logs = InMemoryLogRecordExporter.create()
    private val metrics = InMemoryMetricReader.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().setSampler(followLocalParents(Sampler.alwaysOn())).addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(logs)).build())
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
        .build()
    private val backend = OtelTelemetryBackend(sdk, spanExporter = spans, logExporter = logs)

    private val traceId = "0af7651916cd43dd8448eb211c80319c"
    private val screen = TelemetryKey.OfString("app.screen.name")

    private fun span(spanId: String, sampled: Boolean, parent: String? = null) = RecordedSpan(
        traceId = traceId,
        spanId = spanId,
        parentSpanId = parent,
        owner = "screen",
        operation = "load",
        start = Clock.System.now() - 2.seconds,
        end = Clock.System.now() - 1.seconds,
        attributes = telemetryAttributesOf(screen to "Home"),
        dimensions = setOf(screen.name),
        outcome = RecordedSpan.Outcome.Ok,
        sampled = sampled,
    )

    @Test
    fun keepsClientIdsAndShiftsTimestampsByClockSkew() {
        val clientNow = Clock.System.now() - 1.seconds // client clock runs 1s behind
        val recorded = span("b7ad6b7169203331", sampled = true, parent = "00f067aa0ba902b7")
            .copy(start = clientNow - 300.milliseconds, end = clientNow - 100.milliseconds)
        backend.ingest(TelemetryBatch(telemetryAttributesOf(TelemetryKeys.Service.name to "app"), sentAt = clientNow, spans = listOf(recorded)))

        val exported = spans.finishedSpanItems.single()
        assertEquals("screen.load", exported.name)
        assertEquals(traceId, exported.traceId)
        assertEquals("b7ad6b7169203331", exported.spanId)
        assertEquals("00f067aa0ba902b7", exported.parentSpanId)
        assertEquals(200_000_000, exported.endEpochNanos - exported.startEpochNanos)
        val serverEnd = exported.endEpochNanos / 1_000_000
        val expected = (Clock.System.now() - 100.milliseconds).toEpochMilliseconds()
        assertTrue(serverEnd in expected - 200..expected, "end should be shifted onto the server clock")
        assertEquals("app", exported.resource.attributes.get(AttributeKey.stringKey("service.name")))
    }

    @Test
    fun unsampledSpansAreNotExportedButCountTowardRed() {
        backend.ingest(TelemetryBatch(TelemetryAttributes.empty, Clock.System.now(), spans = listOf(span("b7ad6b7169203331", sampled = false))))

        assertTrue(spans.finishedSpanItems.isEmpty())
        val count = metrics.collectAllMetrics().single { it.name == "screen.client.operation.count" }.longSumData.points.single()
        assertEquals(1L, count.value)
        assertEquals("Home", count.attributes.get(AttributeKey.stringKey("app.screen.name")))
        assertEquals("ok", count.attributes.get(AttributeKey.stringKey("outcome")))
    }

    @Test
    fun logsKeepTraceIdsAndErrors() {
        val error = RecordedError("IllegalStateException", "boom", "at x", fingerprint = "fp")
        backend.ingest(TelemetryBatch(
            TelemetryAttributes.empty, Clock.System.now(),
            logs = listOf(RecordedLog(Clock.System.now(), LogLevel.Error, "failed", TelemetryAttributes.empty, traceId, "b7ad6b7169203331", error)),
        ))

        val log = logs.finishedLogRecordItems.single()
        assertEquals(Severity.ERROR, log.severity)
        assertEquals("failed", log.bodyValue?.asString())
        assertEquals(traceId, log.spanContext.traceId)
        assertEquals("fp", log.attributes.get(AttributeKey.stringKey("error.fingerprint")))
        assertEquals("IllegalStateException", log.attributes.get(AttributeKey.stringKey("exception.type")))
    }

    @Test
    fun metricsGoThroughTheOwnersMeter() {
        val point = RecordedMetric("connectivity", "issues", MetricUnit.Occurrences, RecordedMetric.Kind.Counter, 2.0, TelemetryAttributes.empty)
        backend.ingest(TelemetryBatch(TelemetryAttributes.empty, Clock.System.now(), metrics = listOf(point, point)))

        assertEquals(4.0, metrics.collectAllMetrics().single { it.name == "issues" }.doubleSumData.points.single().value)
    }

    @Test
    fun withoutExportersIngestFails() {
        assertFailsWith<UnsupportedOperationException> {
            OtelTelemetryBackend(sdk).ingest(TelemetryBatch(TelemetryAttributes.empty, Clock.System.now()))
        }
    }

    @Test
    fun traceParentParentsOnlyTheOutermostSpanAndDoesNotDecideSampling() = runBlocking {
        val owner = object : Namespaced {
            override val name = "http"
            override val context: SettingContext = object : SettingContext by TestSettingContext() {
                override val telemetryBackend: TelemetryBackend get() = backend
            }
        }
        withContext(TraceParent("00-$traceId-b7ad6b7169203331-00")) { // client says unsampled
            owner.telemetryTrace("GET /") { owner.telemetryTrace("inner") {} }
        }

        val outer = spans.finishedSpanItems.single { it.name == "http.GET /" }
        val inner = spans.finishedSpanItems.single { it.name == "http.inner" }
        assertEquals(traceId, outer.traceId)
        assertEquals("b7ad6b7169203331", outer.parentSpanId)
        assertEquals(outer.spanId, inner.parentSpanId)
    }
}
