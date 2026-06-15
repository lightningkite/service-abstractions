package com.lightningkite.services.telemetry

/**
 * Typed key for a [TelemetryAttributes] entry. Carries both the attribute name and its Kotlin value
 * type so backends can pre-allocate their native key objects (e.g. OTel [AttributeKey]) exactly once.
 *
 * Pre-allocate instances as `val` fields at service-definition time. Backends cache their native
 * key by [TelemetryKey] equality, so subsequent lookups after warm-up are a single map probe with no
 * allocations.
 *
 * Use [OtelAttributes] for standard OpenTelemetry semantic-convention keys, or define your own:
 * ```kotlin
 * private val cacheHit = TelemetryKey.OfBoolean("cache.hit")
 * private val rowsReturned = TelemetryKey.OfLong("db.response.returned_rows")
 * ```
 */
public sealed class TelemetryKey<T>(public val name: String) {
    final override fun equals(other: Any?): Boolean =
        this === other || (other is TelemetryKey<*> && other::class == this::class && other.name == name)
    final override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    public class OfString(name: String)      : TelemetryKey<String>(name)
    public class OfLong(name: String)        : TelemetryKey<Long>(name)
    public class OfDouble(name: String)      : TelemetryKey<Double>(name)
    public class OfBoolean(name: String)     : TelemetryKey<Boolean>(name)
    public class OfStringList(name: String)  : TelemetryKey<List<String>>(name)
    public class OfLongList(name: String)    : TelemetryKey<List<Long>>(name)
    public class OfDoubleList(name: String)  : TelemetryKey<List<Double>>(name)
    public class OfBooleanList(name: String) : TelemetryKey<List<Boolean>>(name)
}
