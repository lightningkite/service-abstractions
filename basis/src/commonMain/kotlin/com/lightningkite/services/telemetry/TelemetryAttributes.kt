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
@JvmInline
public value class TelemetryAttributes private constructor(public val map: Map<TelemetryKey<*>, Any>) {
    public constructor(entries: List<TelemetryKey.Entry<*>>) : this(
        LinkedHashMap<TelemetryKey<*>, Any>(entries.size).apply {
            for ((key, value) in entries) put(key, value)
        }
    )

    @Deprecated("Use .map (TelemetryKey-keyed) for typed access, or iterate map.entries directly")
    public val raw: Map<String, Any?> get() = map.entries.associate { (k, v) -> k.name to v }

    public val keys: Set<TelemetryKey<*>> get() = map.keys

    public operator fun plus(other: TelemetryAttributes): TelemetryAttributes = TelemetryAttributes(this.map + other.map)

    public operator fun minus(other: TelemetryAttributes): TelemetryAttributes = TelemetryAttributes(this.map.minus(other.map.keys))

    @Suppress("UNCHECKED_CAST")
    public operator fun <T : Any> get(key: TelemetryKey<T>): T? = map[key] as? T


    /**
     * Type-safe builder for [TelemetryAttributes]. Only accepts value types that telemetry backends can
     * represent: String, Long, Double, Boolean, and their array forms. Int and Float are widened
     * automatically. Use [putIfNotNull] to skip a key when the value is absent.
     *
     * Prefer pre-allocated [TelemetryKey] vals (e.g. from [OtelAttributes]) over string-key overloads.
     */
    @MetricAttrDsl
    public class Builder {
        @PublishedApi internal val map: LinkedHashMap<TelemetryKey<*>, Any> = LinkedHashMap()

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

        public fun build(): TelemetryAttributes = TelemetryAttributes(map)
    }

    public companion object {
        public val empty: TelemetryAttributes = TelemetryAttributes(emptyMap())

        /** Backward-compat shim: converts a string-keyed map. Prefer the DSL builder with pre-allocated [TelemetryKey] vals. */
        @Deprecated(
            "Use telemtryAttributesOf() or TelemetryAttributes { ... } builder",
            level = DeprecationLevel.WARNING,
        )
        public fun fromStringMap(raw: Map<String, Any>): TelemetryAttributes =
            TelemetryAttributes(raw.entries.map { (k, v) ->
                when (v) {
                    is Long, is Int   -> TelemetryKey.OfLong(k) to v.toLong()
                    is Double, is Float -> TelemetryKey.OfDouble(k) to v.toDouble()
                    is Boolean -> TelemetryKey.OfBoolean(k) to v
                    is List<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        when (v.firstOrNull()) {
                            null, is String -> TelemetryKey.OfStringList(k) to (v as List<String>)
                            is Long, is Int -> TelemetryKey.OfLongList(k) to (v as List<Number>).map { it.toLong() }
                            is Double, is Float -> TelemetryKey.OfDoubleList(k) to (v as List<Number>).map { it.toDouble() }
                            is Boolean -> TelemetryKey.OfBooleanList(k) to (v as List<Boolean>)
                            else -> throw IllegalArgumentException("Invalid TelemetryAttribute value: $v (List<${v::class}>). Only primitive types and lists of primitives are allowed.")
                        }
                    }
                    else -> throw IllegalArgumentException("Invalid TelemetryAttribute value: $v (${v::class}). Only primitive types and lists of primitives are allowed.")
                }
            })
    }
}

public fun emptyTelemetryAttributes(): TelemetryAttributes = TelemetryAttributes.empty

public fun telemetryAttributesOf(vararg entries: TelemetryKey.Entry<*>): TelemetryAttributes =
    TelemetryAttributes(entries.asList())

public inline fun TelemetryAttributes(block: TelemetryAttributes.Builder.() -> Unit): TelemetryAttributes =
    TelemetryAttributes.Builder().apply(block).build()


@DslMarker internal annotation class MetricAttrDsl

@Deprecated("User .Builder syntax", ReplaceWith("TelemetryAttributes.Builder", "com.lightningkite.services.telemetry.TelemetryAttributes.Builder"))
public typealias TelemetryAttributesBuilder = TelemetryAttributes.Builder