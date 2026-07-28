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
public sealed class TelemetryKey<T : Any>(public val name: String) {
    public abstract val type: Type

    final override fun equals(other: Any?): Boolean =
        this === other || (other is TelemetryKey<*> && this.type == other.type && other.name == name)

    final override fun hashCode(): Int = name.hashCode() * 31 + type.ordinal
    override fun toString(): String = name

    public enum class Type {
        STRING,
        LONG,
        DOUBLE,
        BOOLEAN,
        STRING_LIST,
        LONG_LIST,
        DOUBLE_LIST,
        BOOLEAN_LIST
    }

    public class OfString(name: String) : TelemetryKey<String>(name) {
        override val type: Type get() = Type.STRING
    }
    public class OfLong(name: String) : TelemetryKey<Long>(name) {
        override val type: Type get() = Type.LONG
    }
    public class OfDouble(name: String) : TelemetryKey<Double>(name) {
        override val type: Type get() = Type.DOUBLE
    }
    public class OfBoolean(name: String) : TelemetryKey<Boolean>(name) {
        override val type: Type get() = Type.BOOLEAN
    }
    public class OfStringList(name: String) : TelemetryKey<List<String>>(name) {
        override val type: Type get() = Type.STRING_LIST
    }
    public class OfLongList(name: String) : TelemetryKey<List<Long>>(name) {
        override val type: Type get() = Type.LONG_LIST
    }
    public class OfDoubleList(name: String) : TelemetryKey<List<Double>>(name) {
        override val type: Type get() = Type.DOUBLE_LIST
    }
    public class OfBooleanList(name: String) : TelemetryKey<List<Boolean>>(name) {
        override val type: Type get() = Type.BOOLEAN_LIST
    }

    public data class Entry<T : Any>(
        val key: TelemetryKey<T>,
        val value: T
    )

    public infix fun to(value: T): Entry<T> = Entry(this, value)
}
