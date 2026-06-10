package com.lightningkite.services.otel

import com.lightningkite.services.LogLevel
import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricKey
import com.lightningkite.services.MetricSpan
import com.lightningkite.services.MetricUnit
import com.lightningkite.services.MetricsBackend
import com.lightningkite.services.Namespaced
import com.lightningkite.services.errorFingerprint
import org.slf4j.event.Level as Slf4jLevel
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenTelemetry-backed implementation of the vendor-neutral [MetricsBackend]. Wire one of these into
 * [com.lightningkite.services.SettingContext.metricsBackend] at startup:
 *
 * ```kotlin
 * val backend = OtelMetricsBackend(openTelemetrySdk)
 * ```
 *
 * [span] opens the operation span (made current via [use] so child spans parent correctly) and
 * records the RED counter + duration histogram *while the span is current*, so the duration point
 * carries a trace exemplar. RED dimensions are resolved at completion, so result-derived dimensions
 * (e.g. `cache.hit`, supplied via [MetricSpan.enrich]) are included. Per-owner [OpenTelemetrySub]s
 * and RED instruments are created lazily and cached by [Namespaced.name].
 */
public class OtelMetricsBackend(private val sdk: OpenTelemetry) : MetricsBackend {
    private val subs = ConcurrentHashMap<String, OpenTelemetrySub>()
    private val reds = ConcurrentHashMap<String, Red>()

    private fun subFor(name: String): OpenTelemetrySub = subs.getOrPut(name) { OpenTelemetrySub(sdk, name) }
    private fun redFor(name: String): Red = reds.getOrPut(name) {
        val sub = subFor(name)
        Red(
            ops = sub.counterBuilder("$name.client.operation.count")
                .setDescription("Count of $name client operations by outcome.")
                .setUnit("{operation}").build(),
            duration = sub.histogramBuilder("$name.client.operation.duration")
                .setDescription("Duration of $name client operations.")
                .setUnit("s").build(),
        )
    }

    override suspend fun <T> span(
        owner: Namespaced,
        opName: String,
        attributes: MetricAttributes,
        dimensions: Set<MetricKey<*>>,
        action: suspend (MetricSpan) -> T,
    ): T {
        val system = owner.name
        val sub = subFor(system)
        val red = redFor(system)
        return sub.spanBuilder("$system.$opName").apply { putAll(attributes) }.use { span ->
            val metricSpan = OtelMetricSpan(span, sub)
            val start = System.nanoTime()
            try {
                val result = action(metricSpan)
                val resolved = buildResolvedMap(attributes, metricSpan.enriched)
                red.record(system, opName, "ok", start, dimensions, resolved)
                result
            } catch (c: CancellationException) {
                throw c // not an error; left un-recorded as a RED outcome
            } catch (t: Throwable) {
                val resolved = buildResolvedMap(attributes, metricSpan.enriched)
                red.record(system, opName, "error", start, dimensions, resolved)
                throw t
            }
        }
    }

    override fun histogram(owner: Namespaced, name: String, unit: MetricUnit): MetricsBackend.RawHistogram {
        val h = subFor(owner.name).histogramBuilder(name).setUnit(unit.ucum).build()
        return object : MetricsBackend.RawHistogram {
            override fun record(amount: Double, attributes: MetricAttributes) = h.record(amount, attributes.toOtel())
        }
    }

    override fun counter(owner: Namespaced, name: String, unit: MetricUnit): MetricsBackend.RawCounter {
        val c = subFor(owner.name).counterBuilder(name).ofDoubles().setUnit(unit.ucum).build()
        return object : MetricsBackend.RawCounter {
            override fun add(amount: Double, attributes: MetricAttributes) = c.add(amount, attributes.toOtel())
        }
    }

    override fun inFlight(owner: Namespaced, name: String): MetricsBackend.RawInFlight {
        val c = subFor(owner.name).upDownCounterBuilder(name).build()
        return object : MetricsBackend.RawInFlight {
            override fun adjust(delta: Long, attributes: MetricAttributes) = c.add(delta, attributes.toOtel())
        }
    }

    override fun gauge(
        owner: Namespaced,
        name: String,
        unit: MetricUnit,
        attributes: MetricAttributes,
        sample: () -> Long,
    ): AutoCloseable {
        val otelAttributes = attributes.toOtel()
        return subFor(owner.name).gaugeBuilder(name).setUnit(unit.ucum).ofLongs()
            .buildWithCallback { it.record(sample(), otelAttributes) }
    }

    /**
     * Records [throwable] on the active span if one exists (recordException + ERROR status +
     * `error.fingerprint`), otherwise emits a standalone OTel ERROR log record. [attributes] are
     * attached either way.
     */
    override fun reportError(throwable: Throwable, attributes: MetricAttributes) {
        val fingerprint = throwable.errorFingerprint()

        val span = Span.current()
        if (span.spanContext.isValid) {
            span.recordException(throwable)
            span.setStatus(StatusCode.ERROR, throwable.message ?: throwable.javaClass.name)
            span.setAttribute(errorFingerprintKey, fingerprint)
            for ((key, value) in attributes.map) span.put(key, value)
            return
        }

        // No active span (e.g. background work): emit a standalone OTel log record.
        val logger = sdk.logsBridge.get("com.lightningkite.services")
        logger.logRecordBuilder()
            .setSeverity(Severity.ERROR)
            .setSeverityText("ERROR")
            .setBody(throwable.message ?: throwable.javaClass.name)
            .setAttribute(errorFingerprintKey, fingerprint)
            .apply {
                setAttribute(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
                throwable.message?.let { setAttribute(AttributeKey.stringKey("exception.message"), it) }
                setAttribute(AttributeKey.stringKey("exception.stacktrace"), throwable.stackTraceToString())
                for ((key, value) in attributes.map) if (value != null) setAttribute(AttributeKey.stringKey(key.name), value.toString())
            }
            .emit()
    }

    private companion object {
        private val errorFingerprintKey: AttributeKey<String> = AttributeKey.stringKey("error.fingerprint")
    }

    /** RED instruments for one owner, recorded together with completion-resolved dimensions. */
    private class Red(val ops: LongCounter, val duration: DoubleHistogram) {
        private val systemKey    = AttributeKey.stringKey("system")
        private val operationKey = AttributeKey.stringKey("operation")
        private val outcomeKey   = AttributeKey.stringKey("outcome")

        fun record(
            system: String,
            operation: String,
            outcome: String,
            startNanos: Long,
            dimensions: Set<MetricKey<*>>,
            resolved: Map<MetricKey<*>, Any?>,
        ) {
            val builder = Attributes.builder()
                .put(systemKey, system)
                .put(operationKey, operation)
                .put(outcomeKey, outcome)
            for (key in dimensions) {
                val value = resolved[key]
                if (value != null) builder.put(key, value)
            }
            val attrs = builder.build()
            ops.add(1, attrs)
            duration.record((System.nanoTime() - startNanos) / 1_000_000_000.0, attrs)
        }
    }
}

/** Merges [base] attributes with [enriched], enriched wins on key conflict. */
private fun buildResolvedMap(base: MetricAttributes, enriched: Map<MetricKey<*>, Any?>): Map<MetricKey<*>, Any?> {
    if (base.map.isEmpty()) return enriched
    if (enriched.isEmpty()) return base.map
    val result = LinkedHashMap<MetricKey<*>, Any?>(base.map.size + enriched.size)
    result.putAll(base.map)
    result.putAll(enriched)
    return result
}

/** OTel key cache: maps a [MetricKey] to the corresponding [AttributeKey], allocated once per process. */
private val keyCache = ConcurrentHashMap<MetricKey<*>, AttributeKey<*>>()

@Suppress("UNCHECKED_CAST")
private fun MetricKey<*>.toOtelKey(): AttributeKey<*> = keyCache.getOrPut(this) {
    when (this) {
        is MetricKey.OfString      -> AttributeKey.stringKey(name)
        is MetricKey.OfLong        -> AttributeKey.longKey(name)
        is MetricKey.OfDouble      -> AttributeKey.doubleKey(name)
        is MetricKey.OfBoolean     -> AttributeKey.booleanKey(name)
        is MetricKey.OfStringList  -> AttributeKey.stringArrayKey(name)
        is MetricKey.OfLongList    -> AttributeKey.longArrayKey(name)
        is MetricKey.OfDoubleList  -> AttributeKey.doubleArrayKey(name)
        is MetricKey.OfBooleanList -> AttributeKey.booleanArrayKey(name)
    }
}

/** Records `enrich`ed attributes onto the span and remembers them so RED dimensions can read them.
 *  Log calls are routed through [logger] (SLF4J), which the OTel Logback appender will automatically
 *  correlate to the span because it is current via [asContextElement]. */
private class OtelMetricSpan(private val span: Span, private val logger: org.slf4j.Logger) : MetricSpan {
    val enriched: MutableMap<MetricKey<*>, Any?> = HashMap()

    override fun enrich(attributes: MetricAttributes) {
        for ((key, value) in attributes.map) {
            span.put(key, value)
            enriched[key] = value
        }
    }

    override fun isLoggable(level: LogLevel): Boolean = logger.isEnabledForLevel(level.toSlf4j())

    override fun log(level: LogLevel, message: String, attributes: MetricAttributes) {
        var builder = logger.atLevel(level.toSlf4j())
        for ((key, value) in attributes.map) builder = builder.addKeyValue(key.name, value?.toString() ?: "null")
        builder.log(message)
    }
}

private fun LogLevel.toSlf4j(): Slf4jLevel = when (this) {
    LogLevel.Trace -> Slf4jLevel.TRACE
    LogLevel.Debug -> Slf4jLevel.DEBUG
    LogLevel.Info  -> Slf4jLevel.INFO
    LogLevel.Warn  -> Slf4jLevel.WARN
    LogLevel.Error -> Slf4jLevel.ERROR
}

private fun MetricAttributes.toOtel(): Attributes {
    if (map.isEmpty()) return Attributes.empty()
    val builder = Attributes.builder()
    for ((key, value) in map) builder.put(key, value)
    return builder.build()
}

private fun SpanBuilder.putAll(attributes: MetricAttributes): SpanBuilder = setAllAttributes(attributes.toOtel())

@Suppress("UNCHECKED_CAST")
private fun AttributesBuilder.put(key: MetricKey<*>, value: Any?) {
    if (value == null) return
    when (key) {
        is MetricKey.OfString      -> put(key.toOtelKey() as AttributeKey<String>, value as String)
        is MetricKey.OfLong        -> put(key.toOtelKey() as AttributeKey<Long>, when (value) { is Int -> value.toLong(); else -> value as Long })
        is MetricKey.OfDouble      -> put(key.toOtelKey() as AttributeKey<Double>, when (value) { is Float -> value.toDouble(); else -> value as Double })
        is MetricKey.OfBoolean     -> put(key.toOtelKey() as AttributeKey<Boolean>, value as Boolean)
        is MetricKey.OfStringList  -> put(key.toOtelKey() as AttributeKey<List<String>>, value as List<String>)
        is MetricKey.OfLongList    -> put(key.toOtelKey() as AttributeKey<List<Long>>, value as List<Long>)
        is MetricKey.OfDoubleList  -> put(key.toOtelKey() as AttributeKey<List<Double>>, value as List<Double>)
        is MetricKey.OfBooleanList -> put(key.toOtelKey() as AttributeKey<List<Boolean>>, value as List<Boolean>)
    }
}


@Suppress("UNCHECKED_CAST")
private fun Span.put(key: MetricKey<*>, value: Any?) {
    if (value == null) return
    when (key) {
        is MetricKey.OfString      -> setAttribute(key.toOtelKey() as AttributeKey<String>, value as String)
        is MetricKey.OfLong        -> setAttribute(key.toOtelKey() as AttributeKey<Long>, when (value) { is Int -> value.toLong(); else -> value as Long })
        is MetricKey.OfDouble      -> setAttribute(key.toOtelKey() as AttributeKey<Double>, when (value) { is Float -> value.toDouble(); else -> value as Double })
        is MetricKey.OfBoolean     -> setAttribute(key.toOtelKey() as AttributeKey<Boolean>, value as Boolean)
        is MetricKey.OfStringList  -> setAttribute(key.toOtelKey() as AttributeKey<List<String>>, value as List<String>)
        is MetricKey.OfLongList    -> setAttribute(key.toOtelKey() as AttributeKey<List<Long>>, value as List<Long>)
        is MetricKey.OfDoubleList  -> setAttribute(key.toOtelKey() as AttributeKey<List<Double>>, value as List<Double>)
        is MetricKey.OfBooleanList -> setAttribute(key.toOtelKey() as AttributeKey<List<Boolean>>, value as List<Boolean>)
    }
}
