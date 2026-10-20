package com.lightningkite.services.telemetry

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TelemetryBatchSerializationTest {
    private val allTypes = TelemetryAttributes {
        put(TelemetryKey.OfString("s"), "text")
        put(TelemetryKey.OfLong("l"), 3L)
        put(TelemetryKey.OfDouble("d"), 2.0)
        put(TelemetryKey.OfBoolean("b"), true)
        put(TelemetryKey.OfStringList("sl"), listOf("a", "b"))
        put(TelemetryKey.OfLongList("ll"), listOf(1L, 2L))
        put(TelemetryKey.OfDoubleList("dl"), listOf(1.0, 2.5))
        put(TelemetryKey.OfBooleanList("bl"), listOf(true, false))
    }

    @Test
    fun attributesKeepTheirTypes() {
        val decoded = Json.decodeFromString(TelemetryAttributes.serializer(), Json.encodeToString(TelemetryAttributes.serializer(), allTypes))
        assertEquals(allTypes, decoded)
        assertEquals(allTypes.keys.map { it.type }, decoded.keys.map { it.type })
    }

    @Test
    fun wholeNumberWrittenWithoutDecimalPointStaysDouble() {
        // What Kotlin/JS emits for 2.0
        val decoded = Json.decodeFromString(TelemetryAttributes.serializer(), """{"d":{"x":2}}""")
        assertEquals(2.0, decoded[TelemetryKey.OfDouble("x")])
    }

    @Test
    fun batchRoundTrip() {
        val time = Instant.parse("2026-10-15T12:00:00.123456Z")
        val error = RecordedError(type = "IllegalStateException", message = "boom", stacktrace = "at x", fingerprint = "abc")
        val batch = TelemetryBatch(
            resource = telemetryAttributesOf(TelemetryKeys.Service.name to "app"),
            sentAt = time,
            spans = listOf(RecordedSpan(
                traceId = "0af7651916cd43dd8448eb211c80319c",
                spanId = "b7ad6b7169203331",
                parentSpanId = "00f067aa0ba902b7",
                owner = "http",
                operation = "GET",
                start = time,
                end = time,
                attributes = allTypes,
                outcome = RecordedSpan.Outcome.Cancelled,
                error = error,
            )),
            logs = listOf(RecordedLog(time, LogLevel.Error, "failed", allTypes, traceId = "t", spanId = "s", error = error)),
        )
        assertEquals(batch, Json.decodeFromString(TelemetryBatch.serializer(), Json.encodeToString(TelemetryBatch.serializer(), batch)))
    }
}
