package com.lightningkite.services.otel

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricSpan
import com.lightningkite.services.MetricUnit
import com.lightningkite.services.MetricsBackend
import com.lightningkite.services.Namespaced
import com.lightningkite.services.errorFingerprint
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
        dimensions: Set<String>,
        action: suspend (MetricSpan) -> T,
    ): T {
        val system = owner.name
        val red = redFor(system)
        return subFor(system).spanBuilder("$system.$opName").apply { putAll(attributes) }.use { span ->
            val metricSpan = OtelMetricSpan(span)
            val start = System.nanoTime()
            try {
                val result = action(metricSpan)
                red.record(system, opName, "ok", start, dimensions, attributes, metricSpan.enriched)
                result
            } catch (c: CancellationException) {
                throw c // not an error; left un-recorded as a RED outcome
            } catch (t: Throwable) {
                red.record(system, opName, "error", start, dimensions, attributes, metricSpan.enriched)
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
            for ((key, value) in attributes.raw) span.put(key, value)
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
                for ((key, value) in attributes.raw) if (value != null) setAttribute(AttributeKey.stringKey(key), value.toString())
            }
            .emit()
    }

    private companion object {
        private val errorFingerprintKey: AttributeKey<String> = AttributeKey.stringKey("error.fingerprint")
    }

    /** RED instruments for one owner, recorded together with completion-resolved dimensions. */
    private class Red(val ops: LongCounter, val duration: DoubleHistogram) {
        private val systemKey = AttributeKey.stringKey("system")
        private val operationKey = AttributeKey.stringKey("operation")
        private val outcomeKey = AttributeKey.stringKey("outcome")

        fun record(
            system: String,
            operation: String,
            outcome: String,
            startNanos: Long,
            dimensions: Set<String>,
            attributes: MetricAttributes,
            enriched: Map<String, Any?>,
        ) {
            val builder = Attributes.builder()
                .put(systemKey, system)
                .put(operationKey, operation)
                .put(outcomeKey, outcome)
            for (key in dimensions) {
                val value = if (enriched.containsKey(key)) enriched[key] else attributes.raw[key]
                if (value != null) builder.put(key, value)
            }
            val attrs = builder.build()
            ops.add(1, attrs)
            duration.record((System.nanoTime() - startNanos) / 1_000_000_000.0, attrs)
        }
    }
}

/** Records `enrich`ed attributes onto the span and remembers them so RED dimensions can read them. */
private class OtelMetricSpan(private val span: Span) : MetricSpan {
    val enriched: MutableMap<String, Any?> = HashMap()
    override fun enrich(attributes: MetricAttributes) {
        for ((key, value) in attributes.raw) {
            span.put(key, value)
            enriched[key] = value
        }
    }
}

private fun MetricAttributes.toOtel(): Attributes {
    if (raw.isEmpty()) return Attributes.empty()
    val builder = Attributes.builder()
    for ((key, value) in raw) builder.put(key, value)
    return builder.build()
}

private fun SpanBuilder.putAll(attributes: MetricAttributes): SpanBuilder = setAllAttributes(attributes.toOtel())

private fun AttributesBuilder.put(key: String, value: Any?) {
    when (value) {
        null -> {}
        is String -> put(AttributeKey.stringKey(key), value)
        is Boolean -> put(AttributeKey.booleanKey(key), value)
        is Long -> put(AttributeKey.longKey(key), value)
        is Int -> put(AttributeKey.longKey(key), value.toLong())
        is Double -> put(AttributeKey.doubleKey(key), value)
        is Float -> put(AttributeKey.doubleKey(key), value.toDouble())
        else -> put(AttributeKey.stringKey(key), value.toString())
    }
}

private fun Span.put(key: String, value: Any?) {
    when (value) {
        null -> {}
        is String -> setAttribute(AttributeKey.stringKey(key), value)
        is Boolean -> setAttribute(AttributeKey.booleanKey(key), value)
        is Long -> setAttribute(AttributeKey.longKey(key), value)
        is Int -> setAttribute(AttributeKey.longKey(key), value.toLong())
        is Double -> setAttribute(AttributeKey.doubleKey(key), value)
        is Float -> setAttribute(AttributeKey.doubleKey(key), value.toDouble())
        else -> setAttribute(AttributeKey.stringKey(key), value.toString())
    }
}
