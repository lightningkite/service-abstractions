package com.lightningkite.services

import kotlin.coroutines.coroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.jvm.JvmInline
import kotlinx.coroutines.withContext

/*
 * Coroutine-first metrics model.
 *
 * The unifying primitive is the *ambient attribute bag*: attributes are carried in the coroutine
 * context and accumulate as you descend the call tree ([metricsAttributes]). Everything emitted
 * beneath inherits them, so call sites never decide cardinality.
 *
 * - **Spans** receive the full ambient bag (high cardinality is fine on a trace).
 * - **Metrics** project the ambient bag down to each instrument's `defaultDimensions` — the
 *   low-cardinality keys promoted to dimensions. That declaration, made once next to the instrument,
 *   is the cardinality firewall.
 *
 * The telemetry backend is provided by [SettingContext.metricsBackend] (a plain interface — no
 * expect/actual). When it is `null` (no backend, or a platform without one) every function below
 * degrades to running the action with no-op instruments. Parenting flows through the coroutine
 * context, so child spans nest with no manual threading.
 */

/**
 * Enriches the ambient attribute bag for the duration of [action]; does not open a span. Nested
 * calls accumulate, child keys overriding parent keys. Everything recorded within [action] — spans
 * and projected metrics alike — inherits these attributes.
 */
public suspend fun <T> metricsAttributes(attributes: MetricAttributes, action: suspend () -> T): T {
    if (attributes.map.isEmpty()) return action()
    val parent = coroutineContext[MetricAttributeElement]
    return withContext(MetricAttributeElement(attributes, parent)) { action() }
}

/**
 * Enriches the ambient bag with [attributes], opens a span (parented via the coroutine context),
 * runs [action], and records RED metrics tagged `{name, operation, outcome}`. The span carries the
 * full ambient bag; [MetricSpan.enrich] attaches attributes discovered *during* the operation (rows
 * returned, sanitized query, error code) to the span.
 *
 * [dimensions] names low-cardinality keys to additionally promote onto the RED counter/duration
 * (e.g. `cache.hit`). Their values are taken at completion from the ambient bag and anything
 * `enrich`ed during the operation, so result-derived dimensions work. Keep this set small — it is
 * the cardinality firewall for the trace's metrics.
 */
public suspend fun <T> Namespaced.metricsTrace(
    opName: String,
    attributes: MetricAttributes = MetricAttributes.empty,
    dimensions: Set<MetricKey<*>> = emptySet(),
    action: suspend (MetricSpan) -> T,
): T {
    val owner = this
    val backend = context.metricsBackend ?: return metricsAttributes(attributes) { action(NoopMetricSpan) }
    return metricsAttributes(attributes) {
        backend.span(owner, opName, currentMetricAttributes(), dimensions, action)
    }
}

/**
 * Instruments scoped to the [Namespaced] owner; `defaultDimensions` = the promotable (low-cardinality)
 * keys pulled from the ambient bag at record time. `unit` follows OTel base-unit convention. Create
 * once and hold in a `val`; these are cheap no-ops when no backend is configured.
 */
public fun Namespaced.metricsHistogram(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>): Histogram {
    val raw = context.metricsBackend?.histogram(this, name, unit) ?: return NoopHistogram
    return BackedHistogram(raw, defaultDimensions)
}

public fun Namespaced.metricsCounter(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>): Counter {
    val raw = context.metricsBackend?.counter(this, name, unit) ?: return NoopCounter
    return BackedCounter(raw, defaultDimensions)
}

public fun Namespaced.metricsInFlight(name: String, defaultDimensions: Set<MetricKey<*>>): InFlight {
    val raw = context.metricsBackend?.inFlight(this, name) ?: return NoopInFlight
    return BackedInFlight(raw, defaultDimensions)
}

/**
 * Registers an observable gauge sampled by the exporter. Note: [sample] runs outside any coroutine,
 * so a gauge carries no ambient attributes — only static ones derived from the owner.
 */
public fun Namespaced.metricsGauge(name: String, unit: MetricUnit, defaultDimensions: Set<MetricKey<*>>, sample: () -> Long): AutoCloseable =
    context.metricsBackend?.gauge(this, name, unit, MetricAttributes.empty, sample) ?: AutoCloseable {}

/**
 * Backend SPI implemented per platform (the JVM implementation wraps OpenTelemetry and lives in
 * otel-jvm). Receives attributes already projected/flattened by the common layer; its only job is to
 * talk to the telemetry system. Returned `Raw*` instruments are recorded against by the common
 * `Backed*` wrappers, which supply the projected ambient attributes.
 */
public interface MetricsBackend {
    /** Opens a span named for [owner]/[opName], makes it current for [action] (coroutine context),
     *  applies [attributes] to the span, records RED `{name, operation, outcome}` plus any
     *  [dimensions] resolved at completion (from [attributes] and values `enrich`ed during the
     *  operation), and ends it. */
    public suspend fun <T> span(owner: Namespaced, opName: String, attributes: MetricAttributes, dimensions: Set<MetricKey<*>>, action: suspend (MetricSpan) -> T): T
    public fun histogram(owner: Namespaced, name: String, unit: MetricUnit): RawHistogram
    public fun counter(owner: Namespaced, name: String, unit: MetricUnit): RawCounter
    public fun inFlight(owner: Namespaced, name: String): RawInFlight
    public fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: MetricAttributes, sample: () -> Long): AutoCloseable

    /**
     * Reports an already-caught [throwable] to the telemetry system. On a backend with an active
     * span this records the exception on that span and marks it errored; otherwise it emits a
     * standalone ERROR log record. [attributes] are attached for diagnostics.
     */
    public fun reportError(throwable: Throwable, attributes: MetricAttributes)

    public interface RawHistogram { public fun record(amount: Double, attributes: MetricAttributes) }
    public interface RawCounter { public fun add(amount: Double, attributes: MetricAttributes) }
    public interface RawInFlight { public fun adjust(delta: Long, attributes: MetricAttributes) }
}

/**
 * Unit of a metric instrument, following OpenTelemetry's UCUM-based, base-unit convention (durations
 * in seconds, sizes in bytes). [ucum] is the unit string handed to the backend.
 */
public enum class MetricUnit(public val ucum: String) {
    Seconds("s"),
    Bytes("By"),
    Occurrences("{occurrence}"),
}

public enum class LogLevel { Trace, Debug, Info, Warn, Error }

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

/**
 * Handle to the span opened by [metricsTrace], for attaching attributes discovered while the
 * operation runs. These land on the span only (high cardinality is fine); they do not become metric
 * dimensions. For attributes that should also flow to nested work, use [metricsAttributes] instead.
 *
 * Use [log] (lazy overload) to emit a log record correlated to this span without paying string
 * construction cost when the level is disabled.
 */
public interface MetricSpan {
    public fun enrich(attributes: MetricAttributes)
    /** Returns true if a log at [level] would actually be recorded. Use to guard expensive message construction. */
    public fun isLoggable(level: LogLevel): Boolean
    public fun log(level: LogLevel, message: String, attributes: MetricAttributes = MetricAttributes.empty)
}

/** Lazy overload: [message] is only called when [MetricSpan.isLoggable] returns true for [level]. */
public inline fun MetricSpan.log(level: LogLevel, message: () -> String) {
    if (isLoggable(level)) log(level, message())
}

public interface Histogram {
    /**
     * Records a single observation (e.g. rows returned, payload bytes) in this histogram's
     * [MetricUnit], projecting the ambient bag to the histogram's `defaultDimensions`.
     *
     * For *durations*, use [metricsTrace] instead — a span gives you the timing plus full context
     * and an exemplar linking the metric back to the trace.
     */
    public suspend fun record(amount: Double)
}

public interface Counter {
    public suspend fun increment(amount: Double = 1.0)
}

public interface InFlight {
    public suspend fun lease(): Lease
}

public interface Lease {
    public fun release()
}

// ---- Ambient attribute plumbing (coroutine context) ----

internal class MetricAttributeElement(
    val attributes: MetricAttributes,
    val parent: MetricAttributeElement?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<MetricAttributeElement>

    fun flattened(): MetricAttributes {
        if (parent == null) return attributes
        val merged = LinkedHashMap<MetricKey<*>, Any?>()
        fun add(element: MetricAttributeElement) {
            element.parent?.let(::add) // root first so children override
            merged.putAll(element.attributes.map)
        }
        add(this)
        return MetricAttributes(merged)
    }
}

/** The full ambient attribute bag accumulated by enclosing [metricsAttributes]/[metricsTrace] calls. */
internal suspend fun currentMetricAttributes(): MetricAttributes =
    coroutineContext[MetricAttributeElement]?.flattened() ?: MetricAttributes.empty

internal fun MetricAttributes.project(keys: Set<MetricKey<*>>): MetricAttributes =
    if (keys.isEmpty() || map.isEmpty()) MetricAttributes.empty
    else MetricAttributes(map.filterKeys { it in keys })

// ---- Backed instruments (supply projected ambient attributes to the backend) ----

private class BackedHistogram(val raw: MetricsBackend.RawHistogram, val dimensions: Set<MetricKey<*>>) : Histogram {
    override suspend fun record(amount: Double) = raw.record(amount, currentMetricAttributes().project(dimensions))
}

private class BackedCounter(val raw: MetricsBackend.RawCounter, val dimensions: Set<MetricKey<*>>) : Counter {
    override suspend fun increment(amount: Double) = raw.add(amount, currentMetricAttributes().project(dimensions))
}

private class BackedInFlight(val raw: MetricsBackend.RawInFlight, val dimensions: Set<MetricKey<*>>) : InFlight {
    override suspend fun lease(): Lease {
        val attributes = currentMetricAttributes().project(dimensions)
        raw.adjust(1, attributes)
        return BackedLease(raw, attributes)
    }
}

private class BackedLease(val raw: MetricsBackend.RawInFlight, val attributes: MetricAttributes) : Lease {
    private var released = false
    override fun release() {
        if (released) return
        released = true
        raw.adjust(-1, attributes)
    }
}

// ---- No-op instruments (no backend configured) ----

internal object NoopMetricSpan : MetricSpan {
    override fun enrich(attributes: MetricAttributes) {}
    override fun isLoggable(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, message: String, attributes: MetricAttributes) {}
}

private object NoopHistogram : Histogram {
    override suspend fun record(amount: Double) {}
}

private object NoopCounter : Counter {
    override suspend fun increment(amount: Double) {}
}

private object NoopInFlight : InFlight {
    override suspend fun lease(): Lease = NoopLease
}

private object NoopLease : Lease {
    override fun release() {}
}
