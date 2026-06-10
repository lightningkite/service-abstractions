package com.lightningkite.services

import kotlin.jvm.JvmInline


/**
 * An immutable bag of typed telemetry attributes. Backed by a [MetricKey]-keyed map so backends
 * can pre-allocate their native key objects (e.g. OTel [AttributeKey]) exactly once.
 *
 * Build instances with the DSL:
 * ```kotlin
 * MetricAttributes {
 *     put(OtelAttributes.Db.system, "mongodb")
 *     put(OtelAttributes.Db.operationName, "find")
 * }
 * ```
 */
@JvmInline public value class MetricAttributes(public val map: Map<MetricKey<*>, Any?>) {
    @Deprecated("Use .map (MetricKey-keyed) for typed access, or iterate map.entries directly")
    public val raw: Map<String, Any?> get() = map.entries.associate { (k, v) -> k.name to v }

    public companion object {
        public val empty: MetricAttributes = MetricAttributes(emptyMap<MetricKey<*>, Any?>())

        public inline operator fun invoke(block: MetricAttributesBuilder.() -> Unit): MetricAttributes =
            MetricAttributesBuilder().apply(block).run { MetricAttributes(map) }

        /** Backward-compat shim: converts a string-keyed map. Prefer the DSL builder with pre-allocated [MetricKey] vals. */
        @Deprecated(
            "Use MetricAttributes { put(MetricKey.OfXxx(name), value) } or MetricAttributes { put(name, value) }",
            level = DeprecationLevel.WARNING,
        )
        public operator fun invoke(raw: Map<String, Any?>): MetricAttributes =
            MetricAttributes(raw.entries.associate { (k, v) ->
                when (v) {
                    is Long, is Int   -> MetricKey.OfLong(k)
                    is Double, is Float -> MetricKey.OfDouble(k)
                    is Boolean        -> MetricKey.OfBoolean(k)
                    is List<*>        -> MetricKey.OfStringList(k)
                    else              -> MetricKey.OfString(k)
                } to v
            })
    }
}

@DslMarker internal annotation class MetricAttrDsl

/**
 * Type-safe builder for [MetricAttributes]. Only accepts value types that telemetry backends can
 * represent: String, Long, Double, Boolean, and their array forms. Int and Float are widened
 * automatically. Use [putIfNotNull] to skip a key when the value is absent.
 *
 * Prefer pre-allocated [MetricKey] vals (e.g. from [OtelAttributes]) over string-key overloads.
 */
@MetricAttrDsl
public class MetricAttributesBuilder {
    @PublishedApi internal val map: LinkedHashMap<MetricKey<*>, Any?> = LinkedHashMap()

    // ---- Pre-allocated MetricKey overloads (preferred) ----
    public fun put(key: MetricKey.OfString, value: String)           { map[key] = value }
    public fun put(key: MetricKey.OfLong, value: Long)               { map[key] = value }
    public fun put(key: MetricKey.OfLong, value: Int)                { map[key] = value.toLong() }
    public fun put(key: MetricKey.OfDouble, value: Double)           { map[key] = value }
    public fun put(key: MetricKey.OfDouble, value: Float)            { map[key] = value.toDouble() }
    public fun put(key: MetricKey.OfBoolean, value: Boolean)         { map[key] = value }
    public fun put(key: MetricKey.OfStringList, value: List<String>) { map[key] = value }
    public fun put(key: MetricKey.OfLongList, value: List<Long>)     { map[key] = value }
    public fun put(key: MetricKey.OfDoubleList, value: List<Double>) { map[key] = value }
    public fun put(key: MetricKey.OfBooleanList, value: List<Boolean>) { map[key] = value }

    public fun putIfNotNull(key: MetricKey.OfString, value: String?)   { if (value != null) map[key] = value }
    public fun putIfNotNull(key: MetricKey.OfLong, value: Long?)       { if (value != null) map[key] = value }
    public fun putIfNotNull(key: MetricKey.OfLong, value: Int?)        { if (value != null) map[key] = value.toLong() }
    public fun putIfNotNull(key: MetricKey.OfDouble, value: Double?)   { if (value != null) map[key] = value }
    public fun putIfNotNull(key: MetricKey.OfDouble, value: Float?)    { if (value != null) map[key] = value.toDouble() }
    public fun putIfNotNull(key: MetricKey.OfBoolean, value: Boolean?) { if (value != null) map[key] = value }

    /** Merges all entries from [other] into this builder. */
    public fun putAll(other: MetricAttributes) { map.putAll(other.map) }
}