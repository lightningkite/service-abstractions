package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.MetricsBackend
import com.lightningkite.services.SettingContext
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.otel.OtelMetricsBackend
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
import kotlin.test.assertTrue

/**
 * Telemetry regression tests for [RedisPubSub] against an embedded Redis.
 *
 * Covers span parenting (item 8) for the publish span and RED-style metrics (item 7) for publish.
 */
class RedisPubSubTelemetryTest {

    private val operationKey = AttributeKey.stringKey("operation")
    private val systemKey = AttributeKey.stringKey("system")

    private fun contextWith(sdk: OpenTelemetrySdk): SettingContext {
        val base = TestSettingContext()
        val backend = OtelMetricsBackend(sdk)
        return object : SettingContext by base {
            override val metricsBackend: MetricsBackend get() = backend
        }
    }

    @Test
    fun publishSpanParentsToCurrentSpan() = runBlocking {
        val spanExporter = InMemorySpanExporter.create()
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build()
            )
            .build()
        val pubsub = RedisPubSub("telemetry-test", contextWith(sdk), client)
        val channel = pubsub.string("parenting-${System.nanoTime()}")

        val parent = sdk.getTracer("test").spanBuilder("parent").startSpan()
        try {
            withContext(parent.asContextElement()) {
                channel.emit("hello")
            }
        } finally {
            parent.end()
        }

        // Span names now derive from the owner instance name: "<name>.<operation>".
        val spans = spanExporter.finishedSpanItems
        val parentData = spans.single { it.name == "parent" }
        val publishSpan = spans.single { it.name == "telemetry-test.publish" }

        assertEquals(
            parentData.spanId,
            publishSpan.parentSpanId,
            "publish span should parent to the surrounding application span",
        )
        assertEquals(parentData.traceId, publishSpan.traceId)

        sdk.close()
    }

    @Test
    fun publishRecordsMetric() = runBlocking {
        val metricReader = InMemoryMetricReader.create()
        val sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
            .build()
        val pubsub = RedisPubSub("metrics-test", contextWith(sdk), client)
        val channel = pubsub.string("metrics-${System.nanoTime()}")

        channel.emit("a")
        channel.emit("b")

        val counts: Map<String, MetricData> =
            metricReader.collectAllMetrics().associateBy { it.name }
        // Metric name + `system` dimension now derive from the owner instance name.
        val opCounter = counts.getValue("metrics-test.client.operation.count")
        val publishPoints = opCounter.longSumData.points
            .filter { it.attributes.get(operationKey) == "publish" }

        assertTrue(publishPoints.isNotEmpty(), "expected publish operation metrics")
        assertTrue(publishPoints.all { it.attributes.get(systemKey) == "metrics-test" })
        assertEquals(2L, publishPoints.sumOf { it.value })

        sdk.close()
    }

    companion object {
        private const val PORT = 16380
        private lateinit var server: RedisServer
        private lateinit var client: RedisClient

        @JvmStatic
        @BeforeClass
        fun startUp() {
            server = RedisServer.builder()
                .port(PORT)
                .setting("bind 127.0.0.1")
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("maxmemory 64M")
                .build()
            server.start()
            client = RedisClient.create("redis://127.0.0.1:$PORT")
        }

        @JvmStatic
        @AfterClass
        fun shutDown() {
            client.shutdown()
            server.stop()
        }
    }
}
