package com.lightningkite.services.telemetry

import kotlin.jvm.JvmInline


/**
 * An immutable bag of typed telemetry attributes. Backed by a [TelemetryKey]-keyed map so backends
 * can pre-allocate their native key objects (e.g. OTel [AttributeKey]) exactly once.
 *
 * Build instances with the DSL:
 * ```kotlin
 * TelemetryAttributes {
 *     put(OtelAttributes.Db.system, "mongodb")
 *     put(OtelAttributes.Db.operationName, "find")
 * }
 * ```
 */
@JvmInline public value class TelemetryAttributes(public val map: Map<TelemetryKey<*>, Any?>) {
    @Deprecated("Use .map (TelemetryKey-keyed) for typed access, or iterate map.entries directly")
    public val raw: Map<String, Any?> get() = map.entries.associate { (k, v) -> k.name to v }

    public companion object {
        public val empty: TelemetryAttributes = TelemetryAttributes(emptyMap<TelemetryKey<*>, Any?>())

        public inline operator fun invoke(block: TelemetryAttributesBuilder.() -> Unit): TelemetryAttributes =
            TelemetryAttributesBuilder().apply(block).run { TelemetryAttributes(map) }

        /** Backward-compat shim: converts a string-keyed map. Prefer the DSL builder with pre-allocated [TelemetryKey] vals. */
        @Deprecated(
            "Use TelemetryAttributes { put(TelemetryKey.OfXxx(name), value) } or TelemetryAttributes { put(name, value) }",
            level = DeprecationLevel.WARNING,
        )
        public operator fun invoke(raw: Map<String, Any?>): TelemetryAttributes =
            TelemetryAttributes(raw.entries.associate { (k, v) ->
                when (v) {
                    is Long, is Int   -> TelemetryKey.OfLong(k)
                    is Double, is Float -> TelemetryKey.OfDouble(k)
                    is Boolean        -> TelemetryKey.OfBoolean(k)
                    is List<*>        -> TelemetryKey.OfStringList(k)
                    else              -> TelemetryKey.OfString(k)
                } to v
            })
    }
}

@DslMarker internal annotation class MetricAttrDsl

/**
 * Type-safe builder for [TelemetryAttributes]. Only accepts value types that telemetry backends can
 * represent: String, Long, Double, Boolean, and their array forms. Int and Float are widened
 * automatically. Use [putIfNotNull] to skip a key when the value is absent.
 *
 * Prefer pre-allocated [TelemetryKey] vals (e.g. from [OtelAttributes]) over string-key overloads.
 */
@MetricAttrDsl
public class TelemetryAttributesBuilder {
    @PublishedApi internal val map: LinkedHashMap<TelemetryKey<*>, Any?> = LinkedHashMap()

    // ---- Pre-allocated TelemetryKey overloads (preferred) ----
    public fun put(key: TelemetryKey.OfString, value: String)           { map[key] = value }
    public fun put(key: TelemetryKey.OfLong, value: Long)               { map[key] = value }
    public fun put(key: TelemetryKey.OfLong, value: Int)                { map[key] = value.toLong() }
    public fun put(key: TelemetryKey.OfDouble, value: Double)           { map[key] = value }
    public fun put(key: TelemetryKey.OfDouble, value: Float)            { map[key] = value.toDouble() }
    public fun put(key: TelemetryKey.OfBoolean, value: Boolean)         { map[key] = value }
    public fun put(key: TelemetryKey.OfStringList, value: List<String>) { map[key] = value }
    public fun put(key: TelemetryKey.OfLongList, value: List<Long>)     { map[key] = value }
    public fun put(key: TelemetryKey.OfDoubleList, value: List<Double>) { map[key] = value }
    public fun put(key: TelemetryKey.OfBooleanList, value: List<Boolean>) { map[key] = value }

    public fun putIfNotNull(key: TelemetryKey.OfString, value: String?)   { if (value != null) map[key] = value }
    public fun putIfNotNull(key: TelemetryKey.OfLong, value: Long?)       { if (value != null) map[key] = value }
    public fun putIfNotNull(key: TelemetryKey.OfLong, value: Int?)        { if (value != null) map[key] = value.toLong() }
    public fun putIfNotNull(key: TelemetryKey.OfDouble, value: Double?)   { if (value != null) map[key] = value }
    public fun putIfNotNull(key: TelemetryKey.OfDouble, value: Float?)    { if (value != null) map[key] = value.toDouble() }
    public fun putIfNotNull(key: TelemetryKey.OfBoolean, value: Boolean?) { if (value != null) map[key] = value }

    /** Merges all entries from [other] into this builder. */
    public fun putAll(other: TelemetryAttributes) { map.putAll(other.map) }
}