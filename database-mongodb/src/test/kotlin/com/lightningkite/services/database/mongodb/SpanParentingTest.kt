package com.lightningkite.services.database.mongodb

import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.SettingContext
import com.lightningkite.services.SharedResources
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.Table
import com.lightningkite.services.otel.OtelTelemetryBackend
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Parenting regression for the `telemetryTrace` operation spans.
 *
 * Driver-level command spans were removed (Phase C of the metrics migration), so the only spans the
 * database emits now are the `telemetryTrace` operation spans. This test starts a parent span, runs a
 * Mongo operation inside `withContext(parentSpan.asContextElement())`, and asserts that the
 * resulting operation span re-parents under the caller's request span.
 */
class SpanParentingTest {

    @Serializable
    private data class Thing(
        override val _id: Uuid = Uuid.random(),
        val value: Int = 0,
    ) : HasId<Uuid>

    private val spanExporter = InMemorySpanExporter.create()
    private val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build()
        )
        .build()

    @AfterTest
    fun teardown() {
        sdk.close()
    }

    private inner class OtelContext : SettingContext {
        override val internalSerializersModule: SerializersModule = EmptySerializersModule()
        override val publicUrl: String = "http://localhost:8080"
        override val sharedResources: SharedResources = SharedResources()
        override val projectName: String = "Test"
        override var clock: Clock = Clock.System

        // The operation span comes from telemetryTrace, which requires a telemetryBackend to be wired.
        override val telemetryBackend: TelemetryBackend = OtelTelemetryBackend(sdk)
    }

    @Test
    fun operationSpanParentsToCallerSpan() = runTest {
        val database = MongoDatabase(
            name = "parenting-test",
            databaseName = "test",
            clientSettings = testMongo(),
            context = OtelContext(),
        )
        val table: Table<Thing> = database.table(Thing.serializer(), "things_parenting")

        val tracer = sdk.getTracer("test-caller")
        val parentSpan = tracer.spanBuilder("request").startSpan()
        try {
            withContext(Context.current().with(parentSpan).asContextElement()) {
                table.insert(listOf(Thing(value = 1)))
                table.count(com.lightningkite.services.database.Condition.Always)
            }
        } finally {
            parentSpan.end()
        }

        val spans: List<SpanData> = spanExporter.finishedSpanItems
        val parentData = spans.single { it.name == "request" }

        // telemetryTrace names operation spans "<owner.name>.<opName>"; the owner is the database name.
        val operationSpans = spans.filter { it.name.startsWith("parenting-test.") }
        check(operationSpans.isNotEmpty()) {
            "Expected at least one telemetryTrace operation span; found: " + spans.map { it.name }.distinct()
        }

        // Each operation span's root ancestor within this trace must be the caller's request span.
        val byId = spans.associateBy { it.spanId }
        fun rootOf(span: SpanData): String {
            var current = span
            while (true) {
                val parent = byId[current.parentSpanId] ?: return current.spanId
                current = parent
            }
        }

        val orphaned = operationSpans.filter { rootOf(it) != parentData.spanId }
        assertTrue(
            orphaned.isEmpty(),
            "telemetryTrace operation spans must re-parent under the caller request span " +
                "(${parentData.spanId}). Orphaned spans: " +
                orphaned.joinToString { "${it.name}[parent=${it.parentSpanId}]" }
        )
    }
}
