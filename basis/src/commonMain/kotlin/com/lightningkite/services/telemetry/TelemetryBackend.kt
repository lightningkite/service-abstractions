package com.lightningkite.services.telemetry

import com.lightningkite.services.HasUrl
import com.lightningkite.services.HasUrlSettingParser
import com.lightningkite.services.Namespaced
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


/**
 * Backend SPI implemented per platform (the JVM implementation wraps OpenTelemetry and lives in
 * otel-jvm). Receives attributes already projected/flattened by the common layer; its only job is to
 * talk to the telemetry system. Returned `Raw*` instruments are recorded against by the common
 * `Backed*` wrappers, which supply the projected ambient attributes.
 */
public interface TelemetryBackend {
    /** Opens a span named for [owner]/[opName], makes it current for [action] (coroutine context),
     *  applies [attributes] to the span, records RED `{name, operation, outcome}` plus any
     *  [dimensions] resolved at completion (from [attributes] and values `enrich`ed during the
     *  operation), and ends it. */
    public suspend fun <T> span(owner: Namespaced, opName: String, attributes: TelemetryAttributes, dimensions: Set<TelemetryKey<*>>, action: suspend (TelemetryTrace) -> T): T
    public fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Histogram
    public fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Counter
    public fun inFlight(owner: Namespaced, name: String, dimensions: Set<TelemetryKey<*>>): InFlight
    public fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: TelemetryAttributes, sample: () -> Long): AutoCloseable

    /**
     * Reports an already-caught [throwable] to the telemetry system. On a backend with an active
     * span this records the exception on that span and marks it errored; otherwise it emits a
     * standalone ERROR log record. [attributes] are attached for diagnostics.
     */
    public fun reportError(throwable: Throwable, attributes: TelemetryAttributes)

    @Serializable
    public data class Settings(
        override val url: String = "noop",
        val batching: BatchingRules? = BatchingRules(),
        val metricReportBatching: BatchingRules? = batching,
        val traceReportBatching: BatchingRules? = batching,
        val logReportBatching: BatchingRules? = batching?.copy(
            maxQueueSize = batching.maxQueueSize * 10,
            maxSize = batching.maxSize * 10
        ),

        // Batch processing limits to prevent unbounded queues and memory exhaustion
        val batchingLimits: BatchingRules = BatchingRules(),

        // Payload size limits to prevent massive individual items
        val spanLimits: SpanLimitSettings = SpanLimitSettings(),

        // Log-specific limits
        val logLimits: LogLimits = LogLimits(),

        // Sampling configuration to reduce data volume
        val sampling: Sampling? = null,

        // Rate limiting to prevent cost spikes (null = unlimited)
        val maxSpansPerSecond: Int? = null,
        val maxLogsPerSecond: Int? = null,
    ) : Setting<TelemetryBackend>, HasUrl {
        @Serializable
        public data class BatchingRules(
            val frequency: Duration = 5.minutes,
            val maxQueueSize: Int = 2048,
            val maxSize: Int = 512,
            val exportTimeout: Duration = 30.seconds,
        )

        @Serializable
        public data class LogLimits(
            val maxBodyLength: Int = 8192,
            val maxStackTraceDepth: Int = 50,
        )

        @Serializable
        public data class Sampling(
            val ratio: Double = 1.0,
            val parentBased: Boolean = true,
        )

        @Serializable
        public data class SpanLimitSettings(
            val maxAttributeValueLength: Int = 1024,
            val maxNumberOfAttributes: Int = 128,
            val maxNumberOfEvents: Int = 128,
            val maxNumberOfLinks: Int = 128,
            val maxNumberOfAttributesPerEvent: Int = 32,
            val maxNumberOfAttributesPerLink: Int = 32,
        )
        public companion object : HasUrlSettingParser<Settings, TelemetryBackend>() {
            init {
                register("noop") { _, _, _ -> Noop }
            }
        }
        override fun invoke(name: String, context: SettingContext): TelemetryBackend = parse(name, this, context)
    }

    public object Noop : TelemetryBackend {
        private object NoopHistogram : Histogram { override suspend fun record(amount: Double) {} }
        private object NoopCounter : Counter { override suspend fun increment(amount: Double) {} }
        private object NoopLease : Lease { override fun release() {} }
        private object NoopInFlight : InFlight { override suspend fun lease(): Lease = NoopLease }

        override suspend fun <T> span(owner: Namespaced, opName: String, attributes: TelemetryAttributes, dimensions: Set<TelemetryKey<*>>, action: suspend (TelemetryTrace) -> T): T =
            action(NoopTelemetryTrace)
        override fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Histogram = NoopHistogram
        override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Counter = NoopCounter
        override fun inFlight(owner: Namespaced, name: String, dimensions: Set<TelemetryKey<*>>): InFlight = NoopInFlight
        override fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: TelemetryAttributes, sample: () -> Long): AutoCloseable = AutoCloseable {}
        override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {}
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
 * Handle to the span opened by [telemetryTrace], for attaching attributes discovered while the
 * operation runs. These land on the span only (high cardinality is fine); they do not become metric
 * dimensions. For attributes that should also flow to nested work, use [telemetryAttributes] instead.
 *
 * Use [log] (lazy overload) to emit a log record correlated to this span without paying string
 * construction cost when the level is disabled.
 */
public interface TelemetryTrace {
    public fun enrich(attributes: TelemetryAttributes)
    /** Returns true if a log at [level] would actually be recorded. Use to guard expensive message construction. */
    public fun isLoggable(level: LogLevel): Boolean
    public fun log(level: LogLevel, message: String, attributes: TelemetryAttributes = TelemetryAttributes.empty)
}

/** Lazy overload: [message] is only called when [TelemetryTrace.isLoggable] returns true for [level]. */
public inline fun TelemetryTrace.log(level: LogLevel, message: () -> String) {
    if (isLoggable(level)) log(level, message())
}

public interface Histogram {
    /**
     * Records a single observation (e.g. rows returned, payload bytes) in this histogram's
     * [MetricUnit], projecting the ambient bag to the histogram's `defaultDimensions`.
     *
     * For *durations*, use [telemetryTrace] instead — a span gives you the timing plus full context
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


// ---- No-op span (used by TelemetryBackend.Noop.span) ----

internal object NoopTelemetryTrace : TelemetryTrace {
    override fun enrich(attributes: TelemetryAttributes) {}
    override fun isLoggable(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, message: String, attributes: TelemetryAttributes) {}
}
