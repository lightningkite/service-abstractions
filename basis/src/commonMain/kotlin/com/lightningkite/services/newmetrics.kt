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
    if (attributes.raw.isEmpty()) return action()
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
    dimensions: Set<String> = emptySet(),
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
public fun Namespaced.metricsHistogram(name: String, unit: MetricUnit, defaultDimensions: Set<String>): Histogram {
    val raw = context.metricsBackend?.histogram(this, name, unit) ?: return NoopHistogram
    return BackedHistogram(raw, defaultDimensions)
}

public fun Namespaced.metricsCounter(name: String, unit: MetricUnit, defaultDimensions: Set<String>): Counter {
    val raw = context.metricsBackend?.counter(this, name, unit) ?: return NoopCounter
    return BackedCounter(raw, defaultDimensions)
}

public fun Namespaced.metricsInFlight(name: String, defaultDimensions: Set<String>): InFlight {
    val raw = context.metricsBackend?.inFlight(this, name) ?: return NoopInFlight
    return BackedInFlight(raw, defaultDimensions)
}

/**
 * Registers an observable gauge sampled by the exporter. Note: [sample] runs outside any coroutine,
 * so a gauge carries no ambient attributes — only static ones derived from the owner.
 */
public fun Namespaced.metricsGauge(name: String, unit: MetricUnit, defaultDimensions: Set<String>, sample: () -> Long): AutoCloseable =
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
    public suspend fun <T> span(owner: Namespaced, opName: String, attributes: MetricAttributes, dimensions: Set<String>, action: suspend (MetricSpan) -> T): T
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

// TODO: Make builder limiting types to those a backend accepts (String/Long/Double/Boolean + arrays).
@JvmInline public value class MetricAttributes(public val raw: Map<String, Any?>) {
    public companion object {
        public val empty: MetricAttributes = MetricAttributes(emptyMap())
    }
}

/**
 * Handle to the span opened by [metricsTrace], for attaching attributes discovered while the
 * operation runs. These land on the span only (high cardinality is fine); they do not become metric
 * dimensions. For attributes that should also flow to nested work, use [metricsAttributes] instead.
 */
public interface MetricSpan {
    public fun enrich(attributes: MetricAttributes)
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
        val merged = HashMap<String, Any?>()
        fun add(element: MetricAttributeElement) {
            element.parent?.let(::add) // root first so children override
            merged.putAll(element.attributes.raw)
        }
        add(this)
        return MetricAttributes(merged)
    }
}

/** The full ambient attribute bag accumulated by enclosing [metricsAttributes]/[metricsTrace] calls. */
internal suspend fun currentMetricAttributes(): MetricAttributes =
    coroutineContext[MetricAttributeElement]?.flattened() ?: MetricAttributes.empty

internal fun MetricAttributes.project(keys: Set<String>): MetricAttributes =
    if (keys.isEmpty() || raw.isEmpty()) MetricAttributes.empty
    else MetricAttributes(raw.filterKeys { it in keys })

// ---- Backed instruments (supply projected ambient attributes to the backend) ----

private class BackedHistogram(val raw: MetricsBackend.RawHistogram, val dimensions: Set<String>) : Histogram {
    override suspend fun record(amount: Double) = raw.record(amount, currentMetricAttributes().project(dimensions))
}

private class BackedCounter(val raw: MetricsBackend.RawCounter, val dimensions: Set<String>) : Counter {
    override suspend fun increment(amount: Double) = raw.add(amount, currentMetricAttributes().project(dimensions))
}

private class BackedInFlight(val raw: MetricsBackend.RawInFlight, val dimensions: Set<String>) : InFlight {
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
