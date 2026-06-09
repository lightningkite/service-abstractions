package com.lightningkite.services.cache.redis

import com.lightningkite.services.MetricsBackend
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.otel.OtelMetricsBackend
import com.lightningkite.services.cache.get
import com.lightningkite.services.cache.set
import io.lettuce.core.RedisClient
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import org.junit.BeforeClass
import redis.embedded.RedisServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Telemetry regression tests for [RedisCache] against an embedded Redis.
 *
 * Covers:
 * - **Span parenting**: a `metricsTrace` operation span created while a parent span is current must
 *   parent to that span.
 * - **Metrics**: every public op records a RED-style count, and `get` carries the
 *   `cache.hit` dimension.
 */
class RedisTelemetryTest {

    private val operationKey = AttributeKey.stringKey("operation")
    private val systemKey = AttributeKey.stringKey("system")
    private val outcomeKey = AttributeKey.stringKey("outcome")
    private val cacheHitKey = AttributeKey.booleanKey("cache.hit")

    /**
     * Builds a SettingContext whose [metricsBackend] is an OTel backend wired to the given in-memory
     * exporters, so spans and metrics emitted by the service can be asserted in-process.
     */
    private fun contextWith(sdk: OpenTelemetrySdk): SettingContext {
        val base = TestSettingContext()
        val backend = OtelMetricsBackend(sdk)
        return object : SettingContext by base {
            override val metricsBackend: MetricsBackend get() = backend
        }
    }

    // Plain Lettuce client, matching the `redis` URL factory after driver instrumentation was removed.
    private fun plainClient(): RedisClient = RedisClient.create("redis://127.0.0.1:$PORT/0")

    @Test
    fun operationSpanParentsToCurrentSpan() = runBlocking {
        val spanExporter = InMemorySpanExporter.create()
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build()
            )
            .build()
        val context = contextWith(sdk)
        val cache = RedisCache("telemetry-test", plainClient(), context)

        val tracer = sdk.getTracer("test")
        val parent = tracer.spanBuilder("parent").startSpan()
        try {
            withContext(parent.asContextElement()) {
                cache.get<String>("parenting-key")
            }
        } finally {
            parent.end()
        }

        // Span names now derive from the owner instance name: "<name>.<operation>".
        val spans = spanExporter.finishedSpanItems
        val parentData = spans.single { it.name == "parent" }
        val opSpan = spans.single { it.name == "telemetry-test.get" }

        // The operation span must be a child of the parent span we made current.
        assertEquals(
            parentData.spanId,
            opSpan.parentSpanId,
            "operation span should parent to the surrounding application span",
        )
        // Sanity: same trace.
        assertEquals(parentData.traceId, opSpan.traceId)

        sdk.close()
    }

    @Test
    fun metricsRecordedForOperationsIncludingCacheHit() = runBlocking {
        val metricReader = InMemoryMetricReader.create()
        val spanExporter = InMemorySpanExporter.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build()
            )
            .build()
        val context = contextWith(sdk)
        val cache = RedisCache("metrics-test", plainClient(), context)

        val key = "metrics-key-${System.nanoTime()}"
        cache.remove(key)
        cache.get<String>(key) // miss
        cache.set(key, "value")
        cache.get<String>(key) // hit

        // Metric name + `system` dimension now derive from the owner instance name.
        val counts: Map<String, MetricData> =
            metricReader.collectAllMetrics().associateBy { it.name }
        val opCounter = counts.getValue("metrics-test.client.operation.count")
        val points = opCounter.longSumData.points

        // Every recorded point carries the system + outcome dimensions.
        assertTrue(points.all { it.attributes.get(systemKey) == "metrics-test" })
        assertTrue(points.all { it.attributes.get(outcomeKey) != null })

        val operations = points.map { it.attributes.get(operationKey) }.toSet()
        assertTrue("set" in operations, "expected a set operation metric, got $operations")
        assertTrue("remove" in operations, "expected a remove operation metric, got $operations")
        assertTrue("get" in operations, "expected a get operation metric, got $operations")

        // cache.hit is a promoted RED dimension again (declared via metricsTrace's `dimensions` and
        // resolved at completion from the enriched span). Both a hit and a miss were issued, so the
        // get counter points must carry cache.hit=true and cache.hit=false.
        val getHits = points.filter { it.attributes.get(operationKey) == "get" }
            .map { it.attributes.get(cacheHitKey) }
            .toSet()
        assertTrue(true in getHits, "expected a cache hit counter dimension, got $getHits")
        assertTrue(false in getHits, "expected a cache miss counter dimension, got $getHits")

        // The same value is also attached to the span (enrich writes both).
        val getSpanHits = spanExporter.finishedSpanItems
            .filter { it.name == "metrics-test.get" }
            .map { it.attributes.get(cacheHitKey) }
            .toSet()
        assertNotNull(getSpanHits, "get spans should exist")
        assertTrue(true in getSpanHits && false in getSpanHits, "expected hit+miss span attributes, got $getSpanHits")

        sdk.close()
    }

    companion object {
        private const val PORT = 6380
        private lateinit var redisServer: RedisServer

        @JvmStatic
        @BeforeClass
        fun start() {
            redisServer = RedisServer.builder()
                .port(PORT)
                .setting("bind 127.0.0.1")
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("maxmemory 128M")
                .build()
            redisServer.start()
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            redisServer.stop()
        }
    }
}
