package com.lightningkite.services.otel

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.MetricUnit
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.Namespaced
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.telemetry.telemetryAttributes
import com.lightningkite.services.telemetry.telemetryCounter
import com.lightningkite.services.telemetry.telemetryGauge
import com.lightningkite.services.telemetry.telemetryHistogram
import com.lightningkite.services.telemetry.telemetryInFlight
import com.lightningkite.services.telemetry.telemetryTrace
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end confirmation that the public metrics API (`telemetryTrace`, `telemetryAttributes`, and the
 * `metrics{Histogram,Counter,InFlight,Gauge}` instruments) actually produces correct OpenTelemetry
 * output via [OtelTelemetryBackend] — inspected through in-memory exporters, not mocks.
 */
class MetricsApiEndToEndTest {
    private val systemKey = AttributeKey.stringKey("system")
    private val operationKey = AttributeKey.stringKey("operation")
    private val outcomeKey = AttributeKey.stringKey("outcome")
    private val cacheHit = AttributeKey.booleanKey("cache.hit")
    private val dbKey = AttributeKey.stringKey("db.key")
    private val tenant = AttributeKey.stringKey("tenant")
    private val requestId = AttributeKey.stringKey("request_id")

    private fun sdk(metrics: InMemoryMetricReader, spans: InMemorySpanExporter): OpenTelemetrySdk =
        OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
            .setTracerProvider(
                SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build(),
            )
            .build()

    private fun owner(sdk: OpenTelemetrySdk): Namespaced {
        val backend = OtelTelemetryBackend(sdk)
        val ctx = object : SettingContext by TestSettingContext() {
            override val telemetryBackend: TelemetryBackend get() = backend
        }
        return object : Namespaced {
            override val name: String = "demo"
            override val context: SettingContext = ctx
        }
    }

    private fun Collection<MetricData>.byName() = associateBy { it.name }

    @Test
    fun traceProducesSpanRedMetricsExemplarAndEnforcesCardinalityFirewall() = runBlocking {
        val metrics = InMemoryMetricReader.create()
        val spans = InMemorySpanExporter.create()
        val sdk = sdk(metrics, spans)
        val owner = owner(sdk)

        val cacheHitKey = TelemetryKey.OfBoolean("cache.hit")
        owner.telemetryTrace(
            "fetch",
            // db.key is high-cardinality (an actual key) — must reach the span but NOT a metric dimension.
            attributes = TelemetryAttributes {
                put(TelemetryKey.OfString("db.key"), "user:42:secret")
                put(TelemetryKey.OfString("cache.system"), "redis")
            },
            dimensions = setOf(cacheHitKey),
        ) { span ->
            span.enrich(TelemetryAttributes { put(cacheHitKey, true) }) // discovered mid-operation
        }

        // --- span carries the full bag (high cardinality is fine on a trace) ---
        val span = spans.finishedSpanItems.single { it.name == "demo.fetch" }
        assertEquals("user:42:secret", span.attributes.get(dbKey))
        assertEquals(true, span.attributes.get(cacheHit))
        assertEquals(StatusCode.UNSET, span.status.statusCode) // success leaves UNSET

        val md = metrics.collectAllMetrics().byName()

        // --- RED counter: {system, operation, outcome} + promoted cache.hit, but NOT the high-card key ---
        val count = md.getValue("demo.client.operation.count").longSumData.points.single()
        assertEquals("demo", count.attributes.get(systemKey))
        assertEquals("fetch", count.attributes.get(operationKey))
        assertEquals("ok", count.attributes.get(outcomeKey))
        assertEquals(true, count.attributes.get(cacheHit))
        assertNull(count.attributes.get(dbKey), "high-cardinality db.key must NOT leak onto a metric")
        assertEquals(1L, count.value)

        // --- duration histogram recorded, and its point carries a trace exemplar (metric -> trace link) ---
        val duration = md.getValue("demo.client.operation.duration").histogramData.points.single()
        assertEquals(1L, duration.count)
        val exemplar = duration.exemplars.firstOrNull()
        assertNotNull(exemplar, "duration point should carry a trace exemplar")
        assertEquals(span.traceId, exemplar.spanContext.traceId, "exemplar must link to the operation's trace")

        sdk.close()
    }

    @Test
    fun thrownOperationRecordsErrorOutcome() = runBlocking {
        val metrics = InMemoryMetricReader.create()
        val spans = InMemorySpanExporter.create()
        val sdk = sdk(metrics, spans)
        val owner = owner(sdk)

        assertFailsWith<IllegalStateException> {
            owner.telemetryTrace("boom") { throw IllegalStateException("kaboom") }
        }

        val span = spans.finishedSpanItems.single { it.name == "demo.boom" }
        assertEquals(StatusCode.ERROR, span.status.statusCode)
        val count = metrics.collectAllMetrics().byName()
            .getValue("demo.client.operation.count").longSumData.points.single()
        assertEquals("error", count.attributes.get(outcomeKey))
        sdk.close()
    }

    @Test
    fun instrumentsRecordWithAmbientAttributesProjectedToDimensions() = runBlocking {
        val metrics = InMemoryMetricReader.create()
        val spans = InMemorySpanExporter.create()
        val sdk = sdk(metrics, spans)
        val owner = owner(sdk)

        val tenantKey = TelemetryKey.OfString("tenant")
        val rows = owner.telemetryHistogram("demo.rows", MetricUnit.Occurrences, setOf(tenantKey))
        val calls = owner.telemetryCounter("demo.calls", MetricUnit.Occurrences, setOf(tenantKey))
        val inflight = owner.telemetryInFlight("demo.inflight", setOf(tenantKey))

        // Ambient bag: tenant is low-card (declared as a dimension); request_id is high-card (not).
        telemetryAttributes(TelemetryAttributes {
            put(tenantKey, "acme")
            put(TelemetryKey.OfString("request_id"), "req-xyz")
        }) {
            rows.record(7.0)
            calls.increment(3.0)
            val lease = inflight.lease()
            // While leased, the up/down counter reads 1.
            val mid = metrics.collectAllMetrics().byName()
            assertEquals(1L, mid.getValue("demo.inflight").longSumData.points.single().value)
            lease.release()
        }

        val md = metrics.collectAllMetrics().byName()

        val rowsPoint = md.getValue("demo.rows").histogramData.points.single()
        assertEquals(7.0, rowsPoint.sum)
        assertEquals("acme", rowsPoint.attributes.get(tenant), "tenant must be projected from the ambient bag")
        assertNull(rowsPoint.attributes.get(requestId), "high-cardinality request_id must NOT be projected")

        val callsPoint = md.getValue("demo.calls").doubleSumData.points.single()
        assertEquals(3.0, callsPoint.value)
        assertEquals("acme", callsPoint.attributes.get(tenant))

        // After release, the in-flight counter is back to 0.
        assertEquals(0L, md.getValue("demo.inflight").longSumData.points.single().value)
        sdk.close()
    }

    @Test
    fun gaugeSamplesCurrentValue() {
        val metrics = InMemoryMetricReader.create()
        val spans = InMemorySpanExporter.create()
        val sdk = sdk(metrics, spans)
        val owner = owner(sdk)

        var poolSize = 5L
        val handle = owner.telemetryGauge("demo.pool", MetricUnit.Occurrences) { poolSize }

        assertEquals(5L, metrics.collectAllMetrics().byName().getValue("demo.pool").longGaugeData.points.single().value)
        poolSize = 9L
        assertEquals(9L, metrics.collectAllMetrics().byName().getValue("demo.pool").longGaugeData.points.single().value)

        handle.close()
        sdk.close()
    }

    @Test
    fun noBackendIsANoOpNotACrash() = runBlocking {
        // A context with no backend: every call must run the action and return, recording nothing.
        val owner = object : Namespaced {
            override val name: String = "demo"
            override val context: SettingContext = TestSettingContext()
        }
        val result = owner.telemetryTrace("op") { 42 }
        assertEquals(42, result)
        owner.telemetryHistogram("h", MetricUnit.Bytes, emptySet()).record(1.0)
        owner.telemetryCounter("c", MetricUnit.Occurrences, emptySet()).increment()
        owner.telemetryInFlight("f", emptySet()).lease().release()
        assertTrue(true) // reached here without a backend → no-op path works
    }
}
