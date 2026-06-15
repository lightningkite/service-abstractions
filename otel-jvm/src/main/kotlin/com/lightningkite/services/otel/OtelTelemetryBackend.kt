package com.lightningkite.services.otel

import com.lightningkite.services.telemetry.Counter
import com.lightningkite.services.telemetry.Histogram
import com.lightningkite.services.telemetry.InFlight
import com.lightningkite.services.telemetry.Lease
import com.lightningkite.services.telemetry.LogLevel
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.telemetry.MetricUnit
import com.lightningkite.services.Namespaced
import com.lightningkite.services.telemetry.currentTelemetryAttributes
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
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder
import io.opentelemetry.sdk.trace.SpanLimits
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.CancellationException
import java.io.File
import org.slf4j.event.Level as Slf4jLevel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * OpenTelemetry-backed implementation of the vendor-neutral [TelemetryBackend]. Wire one of these into
 * [com.lightningkite.services.SettingContext.telemetryBackend] at startup:
 *
 * ```kotlin
 * val backend = OtelTelemetryBackend(openTelemetrySdk)
 * ```
 *
 * [span] opens the operation span (made current via [use] so child spans parent correctly) and
 * records the RED counter + duration histogram *while the span is current*, so the duration point
 * carries a trace exemplar. RED dimensions are resolved at completion, so result-derived dimensions
 * (e.g. `cache.hit`, supplied via [TelemetryTrace.enrich]) are included. Per-owner [OpenTelemetrySub]s
 * and RED instruments are created lazily and cached by [Namespaced.name].
 */
public class OtelTelemetryBackend(private val sdk: OpenTelemetry) : TelemetryBackend {
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
        attributes: TelemetryAttributes,
        dimensions: Set<TelemetryKey<*>>,
        action: suspend (TelemetryTrace) -> T,
    ): T {
        val system = owner.name
        val sub = subFor(system)
        val red = redFor(system)
        return sub.spanBuilder("$system.$opName").apply { putAll(attributes) }.use { span ->
            val metricSpan = OtelTelemetryTrace(span, sub)
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

    override fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Histogram {
        val h = subFor(owner.name).histogramBuilder(name).setUnit(unit.ucum).build()
        return object : Histogram {
            override suspend fun record(amount: Double) = h.record(amount, currentTelemetryAttributes().projectToOtel(dimensions))
        }
    }

    override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Counter {
        val c = subFor(owner.name).counterBuilder(name).ofDoubles().setUnit(unit.ucum).build()
        return object : Counter {
            override suspend fun increment(amount: Double) = c.add(amount, currentTelemetryAttributes().projectToOtel(dimensions))
        }
    }

    override fun inFlight(owner: Namespaced, name: String, dimensions: Set<TelemetryKey<*>>): InFlight {
        val c = subFor(owner.name).upDownCounterBuilder(name).build()
        return object : InFlight {
            override suspend fun lease(): Lease {
                val otelAttrs = currentTelemetryAttributes().projectToOtel(dimensions)
                c.add(1, otelAttrs)
                return object : Lease {
                    override fun release() { c.add(-1, otelAttrs) }
                }
            }
        }
    }

    override fun gauge(
        owner: Namespaced,
        name: String,
        unit: MetricUnit,
        attributes: TelemetryAttributes,
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
    override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {
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

    public companion object {
        internal val errorFingerprintKey: AttributeKey<String> = AttributeKey.stringKey("error.fingerprint")

        init {
            TelemetryBackend.Settings.register("log") { _, settings, context ->
                val resource = resource(context.projectName)
                val sdk = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(settings.buildTracerProvider(LoggingSpanExporter.create()).setResource(resource).build())
                    .setMeterProvider(settings.buildMeterProvider(LoggingMetricExporter.create()).setResource(resource).build())
                    .setLoggerProvider(settings.buildLoggerProvider(SystemOutLogRecordExporter.create()).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk, silenceConsole = true)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("console") { _, settings, context ->
                val resource = resource(context.projectName)
                val sdk = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(settings.buildTracerProvider(PrintSpanExporter).setResource(resource).build())
                    .setMeterProvider(settings.buildMeterProvider(PrintMetricExporter).setResource(resource).build())
                    .setLoggerProvider(settings.buildLoggerProvider(PrintLogExporter).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("otlp-grpc") { _, settings, context ->
                val target = "http://${settings.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4317"}"
                val resource = resource(context.projectName)
                val sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(settings.buildTracerProvider(OtlpGrpcSpanExporter.builder().setEndpoint(target).build()).setResource(resource).build())
                    .setMeterProvider(settings.buildMeterProvider(OtlpGrpcMetricExporter.builder().setEndpoint(target).build()).setResource(resource).build())
                    .setLoggerProvider(settings.buildLoggerProvider(OtlpGrpcLogRecordExporter.builder().setEndpoint(target).build()).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("otlp-http") { _, settings, context ->
                val target = "http://${settings.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4318"}"
                val resource = resource(context.projectName)
                val sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(settings.buildTracerProvider(OtlpHttpSpanExporter.builder().setEndpoint("$target/v1/traces").build()).setResource(resource).build())
                    .setMeterProvider(settings.buildMeterProvider(OtlpHttpMetricExporter.builder().setEndpoint("$target/v1/metrics").build()).setResource(resource).build())
                    .setLoggerProvider(settings.buildLoggerProvider(OtlpHttpLogRecordExporter.builder().setEndpoint("$target/v1/logs").build()).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("otlp-https") { _, settings, context ->
                val target = "https://${settings.url.substringAfter("://", "")}"
                val resource = resource(context.projectName)
                val sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(settings.buildTracerProvider(OtlpHttpSpanExporter.builder().setEndpoint("$target/v1/traces").build()).setResource(resource).build())
                    .setMeterProvider(settings.buildMeterProvider(OtlpHttpMetricExporter.builder().setEndpoint("$target/v1/metrics").build()).setResource(resource).build())
                    .setLoggerProvider(settings.buildLoggerProvider(OtlpHttpLogRecordExporter.builder().setEndpoint("$target/v1/logs").build()).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("dev") { _, settings, context ->
                val urlWithoutScheme = settings.url.substringAfter("://", "").let {
                    if (it.isEmpty()) settings.url.substringAfter("dev:", "") else it
                }
                val pathPart = urlWithoutScheme.substringBefore("?").takeIf { it.isNotBlank() }
                val queryParams = parseQueryParams(urlWithoutScheme)
                val colorEnabled = queryParams["color"]?.lowercase() != "false"
                val outputFile = pathPart?.let { File(it) }
                val logDelayMs = queryParams["log_delay"]?.toLongOrNull() ?: 0
                val metricFrequency = queryParams["metric_frequency"]?.let { Duration.parseOrNull(it) } ?: 60.seconds
                val config = DevExporterConfig(color = colorEnabled, output = outputFile, logCorrelationDelayMs = logDelayMs)
                val resource = resource(context.projectName.ifBlank { "dev" })
                val sdk = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(DevSpanExporter(config))).setResource(resource).setSpanLimits(settings.spanLimits.make()).build())
                    .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(PeriodicMetricReader.builder(DevMetricExporter(config)).setInterval(metricFrequency.toJavaDuration()).build()).setResource(resource).build())
                    .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(DevLogExporter(config))).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk, silenceConsole = true)
                OtelTelemetryBackend(sdk)
            }
            TelemetryBackend.Settings.register("debounced-dev") { _, settings, context ->
                val urlWithoutScheme = settings.url.substringAfter("://", "").let {
                    if (it.isEmpty()) settings.url.substringAfter("debounced-dev:", "") else it
                }
                val pathPart = urlWithoutScheme.substringBefore("?").takeIf { it.isNotBlank() }
                val queryParams = parseQueryParams(urlWithoutScheme)
                val colorEnabled = queryParams["color"]?.lowercase() != "false"
                val outputFile = pathPart?.let { File(it) }
                val debounceWindowMs = queryParams["debounce"]?.toLongOrNull()
                val debounceMinCount = queryParams["debounce_min"]?.toIntOrNull() ?: 1
                val logDelayMs = queryParams["log_delay"]?.toLongOrNull() ?: 0
                val metricFrequency = queryParams["metric_frequency"]?.let { Duration.parseOrNull(it) } ?: 60.seconds
                val config = DevExporterConfig(color = colorEnabled, output = outputFile, debounceWindowMs = debounceWindowMs, debounceMinCount = debounceMinCount, logCorrelationDelayMs = logDelayMs)
                val resource = resource(context.projectName.ifBlank { "dev" })
                val sdk = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(DebouncedDevSpanExporter(config))).setResource(resource).setSpanLimits(settings.spanLimits.make()).build())
                    .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(PeriodicMetricReader.builder(DevMetricExporter(config)).setInterval(metricFrequency.toJavaDuration()).build()).setResource(resource).build())
                    .setLoggerProvider(SdkLoggerProvider.builder().addLogRecordProcessor(SimpleLogRecordProcessor.create(DevLogExporter(config))).setResource(resource).build())
                    .build()
                otelLoggingSetup(sdk, silenceConsole = true)
                OtelTelemetryBackend(sdk)
            }
        }

        /** Call once at startup to register OTel URL schemes with [TelemetryBackend.Settings]. */
        public fun register() {}

        private fun resource(serviceName: String): Resource =
            Resource.getDefault().merge(Resource.builder().put("service.name", serviceName).build())

        private fun parseQueryParams(urlWithoutScheme: String): Map<String, String> {
            val queryPart = if (urlWithoutScheme.contains("?")) urlWithoutScheme.substringAfter("?") else ""
            return queryPart.split("&").filter { it.contains("=") }.associate { it.substringBefore("=") to it.substringAfter("=") }
        }
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
            dimensions: Set<TelemetryKey<*>>,
            resolved: Map<TelemetryKey<*>, Any?>,
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
private fun buildResolvedMap(base: TelemetryAttributes, enriched: Map<TelemetryKey<*>, Any?>): Map<TelemetryKey<*>, Any?> {
    if (base.map.isEmpty()) return enriched
    if (enriched.isEmpty()) return base.map
    val result = LinkedHashMap<TelemetryKey<*>, Any?>(base.map.size + enriched.size)
    result.putAll(base.map)
    result.putAll(enriched)
    return result
}

/** OTel key cache: maps a [TelemetryKey] to the corresponding [AttributeKey], allocated once per process. */
private val keyCache = ConcurrentHashMap<TelemetryKey<*>, AttributeKey<*>>()

@Suppress("UNCHECKED_CAST")
private fun TelemetryKey<*>.toOtelKey(): AttributeKey<*> = keyCache.getOrPut(this) {
    when (this) {
        is TelemetryKey.OfString      -> AttributeKey.stringKey(name)
        is TelemetryKey.OfLong        -> AttributeKey.longKey(name)
        is TelemetryKey.OfDouble      -> AttributeKey.doubleKey(name)
        is TelemetryKey.OfBoolean     -> AttributeKey.booleanKey(name)
        is TelemetryKey.OfStringList  -> AttributeKey.stringArrayKey(name)
        is TelemetryKey.OfLongList    -> AttributeKey.longArrayKey(name)
        is TelemetryKey.OfDoubleList  -> AttributeKey.doubleArrayKey(name)
        is TelemetryKey.OfBooleanList -> AttributeKey.booleanArrayKey(name)
    }
}

/** Records `enrich`ed attributes onto the span and remembers them so RED dimensions can read them.
 *  Log calls are routed through [logger] (SLF4J), which the OTel Logback appender will automatically
 *  correlate to the span because it is current via [asContextElement]. */
private class OtelTelemetryTrace(private val span: Span, private val logger: org.slf4j.Logger) : TelemetryTrace {
    val enriched: MutableMap<TelemetryKey<*>, Any?> = HashMap()

    override fun enrich(attributes: TelemetryAttributes) {
        for ((key, value) in attributes.map) {
            span.put(key, value)
            enriched[key] = value
        }
    }

    override fun isLoggable(level: LogLevel): Boolean = logger.isEnabledForLevel(level.toSlf4j())

    override fun log(level: LogLevel, message: String, attributes: TelemetryAttributes) {
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

private fun TelemetryAttributes.toOtel(): Attributes {
    if (map.isEmpty()) return Attributes.empty()
    val builder = Attributes.builder()
    for ((key, value) in map) builder.put(key, value)
    return builder.build()
}

private fun TelemetryAttributes.projectToOtel(dimensions: Set<TelemetryKey<*>>): Attributes {
    if (dimensions.isEmpty() || map.isEmpty()) return Attributes.empty()
    val builder = Attributes.builder()
    for (key in dimensions) { val v = map[key]; if (v != null) builder.put(key, v) }
    return builder.build()
}

private fun SpanBuilder.putAll(attributes: TelemetryAttributes): SpanBuilder = setAllAttributes(attributes.toOtel())

@Suppress("UNCHECKED_CAST")
private fun AttributesBuilder.put(key: TelemetryKey<*>, value: Any?) {
    if (value == null) return
    when (key) {
        is TelemetryKey.OfString      -> put(key.toOtelKey() as AttributeKey<String>, value as String)
        is TelemetryKey.OfLong        -> put(key.toOtelKey() as AttributeKey<Long>, when (value) { is Int -> value.toLong(); else -> value as Long })
        is TelemetryKey.OfDouble      -> put(key.toOtelKey() as AttributeKey<Double>, when (value) { is Float -> value.toDouble(); else -> value as Double })
        is TelemetryKey.OfBoolean     -> put(key.toOtelKey() as AttributeKey<Boolean>, value as Boolean)
        is TelemetryKey.OfStringList  -> put(key.toOtelKey() as AttributeKey<List<String>>, value as List<String>)
        is TelemetryKey.OfLongList    -> put(key.toOtelKey() as AttributeKey<List<Long>>, value as List<Long>)
        is TelemetryKey.OfDoubleList  -> put(key.toOtelKey() as AttributeKey<List<Double>>, value as List<Double>)
        is TelemetryKey.OfBooleanList -> put(key.toOtelKey() as AttributeKey<List<Boolean>>, value as List<Boolean>)
    }
}


@Suppress("UNCHECKED_CAST")
private fun Span.put(key: TelemetryKey<*>, value: Any?) {
    if (value == null) return
    when (key) {
        is TelemetryKey.OfString      -> setAttribute(key.toOtelKey() as AttributeKey<String>, value as String)
        is TelemetryKey.OfLong        -> setAttribute(key.toOtelKey() as AttributeKey<Long>, when (value) { is Int -> value.toLong(); else -> value as Long })
        is TelemetryKey.OfDouble      -> setAttribute(key.toOtelKey() as AttributeKey<Double>, when (value) { is Float -> value.toDouble(); else -> value as Double })
        is TelemetryKey.OfBoolean     -> setAttribute(key.toOtelKey() as AttributeKey<Boolean>, value as Boolean)
        is TelemetryKey.OfStringList  -> setAttribute(key.toOtelKey() as AttributeKey<List<String>>, value as List<String>)
        is TelemetryKey.OfLongList    -> setAttribute(key.toOtelKey() as AttributeKey<List<Long>>, value as List<Long>)
        is TelemetryKey.OfDoubleList  -> setAttribute(key.toOtelKey() as AttributeKey<List<Double>>, value as List<Double>)
        is TelemetryKey.OfBooleanList -> setAttribute(key.toOtelKey() as AttributeKey<List<Boolean>>, value as List<Boolean>)
    }
}

// ── TelemetryBackend.Settings → OTel SDK builders ────────────────────────────

private fun TelemetryBackend.Settings.Sampling.make(): Sampler = if (parentBased) {
    Sampler.parentBasedBuilder(Sampler.traceIdRatioBased(ratio)).build()
} else {
    Sampler.traceIdRatioBased(ratio)
}

private fun TelemetryBackend.Settings.SpanLimitSettings.make(): SpanLimits =
    SpanLimits.builder()
        .setMaxAttributeValueLength(maxAttributeValueLength)
        .setMaxNumberOfAttributes(maxNumberOfAttributes)
        .setMaxNumberOfEvents(maxNumberOfEvents)
        .setMaxNumberOfLinks(maxNumberOfLinks)
        .setMaxNumberOfAttributesPerEvent(maxNumberOfAttributesPerEvent)
        .setMaxNumberOfAttributesPerLink(maxNumberOfAttributesPerLink)
        .build()

private fun TelemetryBackend.Settings.buildTracerProvider(exporter: SpanExporter): SdkTracerProviderBuilder {
    val wrapped = maxSpansPerSecond?.let { RateLimitedSpanExporter(exporter, it) } ?: exporter
    val sampler = sampling?.make()
    return SdkTracerProvider.builder()
        .let { if (sampler != null) it.setSampler(sampler) else it }
        .addSpanProcessor(
            traceReportBatching?.let {
                BatchSpanProcessor.builder(wrapped)
                    .setScheduleDelay(it.frequency.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setMaxQueueSize(it.maxQueueSize)
                    .setMaxExportBatchSize(it.maxSize)
                    .setExporterTimeout(it.exportTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .build()
            } ?: SimpleSpanProcessor.create(wrapped)
        )
        .setSpanLimits(spanLimits.make())
}

private fun TelemetryBackend.Settings.buildLoggerProvider(exporter: LogRecordExporter): SdkLoggerProviderBuilder {
    val safe = SafeLogRecordExporter(exporter, logLimits.maxBodyLength, logLimits.maxStackTraceDepth)
    val wrapped = maxLogsPerSecond?.let { RateLimitedLogRecordExporter(safe, it) } ?: safe
    return SdkLoggerProvider.builder().addLogRecordProcessor(
        logReportBatching?.let {
            BatchLogRecordProcessor.builder(wrapped)
                .setScheduleDelay(it.frequency.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .setMaxQueueSize(it.maxQueueSize)
                .setMaxExportBatchSize(it.maxSize)
                .setExporterTimeout(it.exportTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
        } ?: SimpleLogRecordProcessor.create(wrapped)
    )
}

private fun TelemetryBackend.Settings.buildMeterProvider(exporter: MetricExporter): SdkMeterProviderBuilder =
    SdkMeterProvider.builder().registerMetricReader(
        PeriodicMetricReader.builder(exporter)
            .setInterval((metricReportBatching?.frequency ?: 5.seconds).inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
    )
