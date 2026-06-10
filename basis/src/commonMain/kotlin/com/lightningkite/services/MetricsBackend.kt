package com.lightningkite.services

import kotlin.coroutines.coroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable


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
    public fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Histogram
    public fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Counter
    public fun inFlight(owner: Namespaced, name: String, dimensions: Set<MetricKey<*>>): InFlight
    public fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: MetricAttributes, sample: () -> Long): AutoCloseable

    /**
     * Reports an already-caught [throwable] to the telemetry system. On a backend with an active
     * span this records the exception on that span and marks it errored; otherwise it emits a
     * standalone ERROR log record. [attributes] are attached for diagnostics.
     */
    public fun reportError(throwable: Throwable, attributes: MetricAttributes)

    /**
     * Configuration for instantiating a [MetricsBackend].
     *
     * The URL scheme determines the backend:
     * - `noop` - Discards all telemetry (default)
     * - `logging` - Human-readable ANSI span tree to stdout (JVM, development)
     * - `logging-nocolor` - Same without ANSI colors (JVM, CI / log-aggregation)
     * - OTel schemes registered by `otel-jvm` (call [OtelMetricsBackend.register] at startup):
     *   `log`, `console`, `dev`, `debounced-dev`, `otlp-grpc`, `otlp-http`, `otlp-https`
     *
     * OTel URL examples forwarded to [OpenTelemetrySettings] with [SettingContext.projectName]
     * as the service name:
     * ```
     * MetricsBackend.Settings("dev://")
     * MetricsBackend.Settings("otlp-grpc://collector:4317")
     * MetricsBackend.Settings("otlp-https://api.honeycomb.io:443")
     * ```
     *
     * For non-default [OpenTelemetrySettings] (custom batching, sampling, rate limits), bypass
     * this class and call `OpenTelemetrySettings(...).metricsBackend(name, context)` directly.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "noop",
    ) : Setting<MetricsBackend> {
        public companion object : UrlSettingParser<MetricsBackend>() {
            init {
                register("noop") { _, _, _ -> Noop }
            }
        }
        override fun invoke(name: String, context: SettingContext): MetricsBackend = parse(name, url, context)
    }

    public object Noop : MetricsBackend {
        private object NoopHistogram : Histogram { override suspend fun record(amount: Double) {} }
        private object NoopCounter : Counter { override suspend fun increment(amount: Double) {} }
        private object NoopLease : Lease { override fun release() {} }
        private object NoopInFlight : InFlight { override suspend fun lease(): Lease = NoopLease }

        override suspend fun <T> span(owner: Namespaced, opName: String, attributes: MetricAttributes, dimensions: Set<MetricKey<*>>, action: suspend (MetricSpan) -> T): T =
            action(NoopMetricSpan)
        override fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Histogram = NoopHistogram
        override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<MetricKey<*>>): Counter = NoopCounter
        override fun inFlight(owner: Namespaced, name: String, dimensions: Set<MetricKey<*>>): InFlight = NoopInFlight
        override fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: MetricAttributes, sample: () -> Long): AutoCloseable = AutoCloseable {}
        override fun reportError(throwable: Throwable, attributes: MetricAttributes) {}
    }
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


// ---- No-op span (used by MetricsBackend.Noop.span) ----

internal object NoopMetricSpan : MetricSpan {
    override fun enrich(attributes: MetricAttributes) {}
    override fun isLoggable(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, message: String, attributes: MetricAttributes) {}
}
