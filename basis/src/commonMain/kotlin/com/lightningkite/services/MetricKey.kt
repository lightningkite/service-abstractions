package com.lightningkite.services

/**
 * Typed key for a [MetricAttributes] entry. Carries both the attribute name and its Kotlin value
 * type so backends can pre-allocate their native key objects (e.g. OTel [AttributeKey]) exactly once.
 *
 * Pre-allocate instances as `val` fields at service-definition time. Backends cache their native
 * key by [MetricKey] equality, so subsequent lookups after warm-up are a single map probe with no
 * allocations.
 *
 * Use [OtelAttributes] for standard OpenTelemetry semantic-convention keys, or define your own:
 * ```kotlin
 * private val cacheHit = MetricKey.OfBoolean("cache.hit")
 * private val rowsReturned = MetricKey.OfLong("db.response.returned_rows")
 * ```
 */
public sealed class MetricKey<T>(public val name: String) {
    final override fun equals(other: Any?): Boolean =
        this === other || (other is MetricKey<*> && other::class == this::class && other.name == name)
    final override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name

    public class OfString(name: String)      : MetricKey<String>(name)
    public class OfLong(name: String)        : MetricKey<Long>(name)
    public class OfDouble(name: String)      : MetricKey<Double>(name)
    public class OfBoolean(name: String)     : MetricKey<Boolean>(name)
    public class OfStringList(name: String)  : MetricKey<List<String>>(name)
    public class OfLongList(name: String)    : MetricKey<List<Long>>(name)
    public class OfDoubleList(name: String)  : MetricKey<List<Double>>(name)
    public class OfBooleanList(name: String) : MetricKey<List<Boolean>>(name)
}
