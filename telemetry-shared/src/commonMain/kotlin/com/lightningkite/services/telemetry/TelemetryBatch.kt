package com.lightningkite.services.telemetry

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Telemetry recorded elsewhere (typically a client app) and sent to a server, which hands it to
 * [TelemetryBackend.ingest]. Ids and timestamps are the recorder's own.
 */
@Serializable
public data class TelemetryBatch(
    val resource: TelemetryAttributes,
    /** Stamped by the recorder at upload; the receiver shifts timestamps by `receivedAt - sentAt` to correct clock skew. */
    val sentAt: Instant,
    val spans: List<RecordedSpan> = listOf(),
    val logs: List<RecordedLog> = listOf(),
)

@Serializable
public data class RecordedSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val owner: String,
    val operation: String,
    val start: Instant,
    val end: Instant,
    val attributes: TelemetryAttributes,
    val outcome: Outcome,
    val error: RecordedError? = null,
) {
    public enum class Outcome { Ok, Error, Cancelled }
}

@Serializable
public data class RecordedLog(
    val time: Instant,
    val level: LogLevel,
    val message: String,
    val attributes: TelemetryAttributes,
    val traceId: String? = null,
    val spanId: String? = null,
    val error: RecordedError? = null,
)

@Serializable
public data class RecordedError(
    val type: String,
    val message: String,
    val stacktrace: String,
    val fingerprint: String,
)

// Grouped by type because a JSON number can't carry it: Kotlin/JS writes 2.0 as 2.
@Serializable
private class TelemetryAttributesJson(
    val s: Map<String, String> = mapOf(),
    val l: Map<String, Long> = mapOf(),
    val d: Map<String, Double> = mapOf(),
    val b: Map<String, Boolean> = mapOf(),
    val sl: Map<String, List<String>> = mapOf(),
    val ll: Map<String, List<Long>> = mapOf(),
    val dl: Map<String, List<Double>> = mapOf(),
    val bl: Map<String, List<Boolean>> = mapOf(),
)

internal object TelemetryAttributesSerializer : KSerializer<TelemetryAttributes> {
    private val json = TelemetryAttributesJson.serializer()
    override val descriptor = json.descriptor

    override fun serialize(encoder: Encoder, value: TelemetryAttributes) {
        @Suppress("UNCHECKED_CAST")
        fun <T> ofType(type: TelemetryKey.Type): Map<String, T> =
            value.map.entries.filter { it.key.type == type }.associate { it.key.name to it.value as T }
        encoder.encodeSerializableValue(json, TelemetryAttributesJson(
            s = ofType(TelemetryKey.Type.STRING),
            l = ofType(TelemetryKey.Type.LONG),
            d = ofType(TelemetryKey.Type.DOUBLE),
            b = ofType(TelemetryKey.Type.BOOLEAN),
            sl = ofType(TelemetryKey.Type.STRING_LIST),
            ll = ofType(TelemetryKey.Type.LONG_LIST),
            dl = ofType(TelemetryKey.Type.DOUBLE_LIST),
            bl = ofType(TelemetryKey.Type.BOOLEAN_LIST),
        ))
    }

    override fun deserialize(decoder: Decoder): TelemetryAttributes {
        val value = decoder.decodeSerializableValue(json)
        return TelemetryAttributes(buildList {
            for ((k, v) in value.s) add(TelemetryKey.OfString(k) to v)
            for ((k, v) in value.l) add(TelemetryKey.OfLong(k) to v)
            for ((k, v) in value.d) add(TelemetryKey.OfDouble(k) to v)
            for ((k, v) in value.b) add(TelemetryKey.OfBoolean(k) to v)
            for ((k, v) in value.sl) add(TelemetryKey.OfStringList(k) to v)
            for ((k, v) in value.ll) add(TelemetryKey.OfLongList(k) to v)
            for ((k, v) in value.dl) add(TelemetryKey.OfDoubleList(k) to v)
            for ((k, v) in value.bl) add(TelemetryKey.OfBooleanList(k) to v)
        })
    }
}
