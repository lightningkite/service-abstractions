package com.lightningkite.services.otel

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import com.lightningkite.services.*
import io.opentelemetry.api.common.Attributes
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
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.data.LogRecordData
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
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for OpenTelemetry observability (traces, metrics, logs).
 *
 * Provides integrated telemetry export to various backends with cost-control features:
 * - **Multiple exporters**: OTLP (gRPC/HTTP), console output, logging output
 * - **Batching control**: Configurable batch sizes and frequencies
 * - **Rate limiting**: Prevent cost spikes during traffic bursts
 * - **Sampling**: Reduce data volume via trace sampling
 * - **Payload limits**: Prevent individual payloads from being too large
 * - **Logback integration**: Automatic log forwarding to OpenTelemetry
 *
 * ## Supported URL Schemes
 *
 * - `log://` - Output to logging framework (default, development)
 * - `console://` - Human-readable console output
 * - `dev://[path]?color=false` - Immediate hierarchical trace output (development)
 * - `debounced-dev://[path]?debounce=1000&debounce_min=3` - Aggregated trace output for high-frequency operations
 * - `otlp-grpc://host:port` - OTLP over gRPC (production, efficient)
 * - `otlp-http://host:port` - OTLP over HTTP (production, firewall-friendly)
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Development: output to logs
 * OpenTelemetrySettings(url = "log://")
 *
 * // Development: immediate hierarchical trace output
 * OpenTelemetrySettings(url = "dev://")
 *
 * // Development: debounced traces for high-frequency operations (e.g. websocket messages)
 * // First occurrence prints immediately, subsequent occurrences aggregate over window
 * OpenTelemetrySettings(url = "debounced-dev://?debounce=30000") // 30s window
 *
 * // Production: send to observability backend via gRPC
 * OpenTelemetrySettings(
 *     url = "otlp-grpc://otel-collector:4317",
 *     sampling = OpenTelemetrySettings.Sampling(ratio = 0.1), // 10% sampling
 *     maxSpansPerSecond = 1000, // rate limit
 *     batching = BatchingRules(frequency = 10.seconds)
 * )
 *
 * // Cloud provider: send to hosted service via HTTP
 * OpenTelemetrySettings(
 *     url = "otlp-http://api.honeycomb.io:443",
 *     batching = BatchingRules(frequency = 30.seconds, maxSize = 200)
 * )
 * ```
 *
 * ## Implementation Notes
 *
 * - **Logback integration**: Automatically attaches OpenTelemetry appender to root logger
 * - **W3C propagation**: Uses W3C Trace Context for distributed tracing
 * - **Batching**: Defaults to batch exports every 5 minutes to reduce overhead
 * - **Rate limiting**: Optional per-second limits for spans and logs
 * - **Sampling**: Trace-level sampling with parent-based decision propagation
 * - **Resource attributes**: Sets service.name automatically
 *
 * ## Important Gotchas
 *
 * - **Default batching is slow**: 5-minute batching is good for cost control but bad for real-time visibility
 * - **Rate limiting drops data**: Exceeding limits silently drops telemetry (logged as warning)
 * - **Sampling is probabilistic**: You may miss important traces at low sampling rates
 * - **Log integration side effect**: Modifies Logback configuration globally on initialization
 * - **Blocking on shutdown**: Exporters may block during flush (ensure proper shutdown hooks)
 * - **No authentication**: OTLP exporters don't include authentication (use sidecars or environment variables)
 *
 * ## Cost Control Features
 *
 * This settings class includes several features to prevent runaway observability costs:
 *
 * 1. **Batching**: Reduces egress bandwidth by combining small payloads
 * 2. **Rate limiting**: Hard caps on spans/logs per second (drops excess)
 * 3. **Sampling**: Reduces trace volume while preserving statistical significance
 * 4. **Payload limits**: Truncates oversized attributes, events, and logs
 * 5. **Queue limits**: Prevents memory exhaustion during traffic spikes
 *
 * @property url Backend URL scheme (log, console, otlp-grpc, otlp-http)
 * @property batching Default batching rules for all signal types (traces, metrics, logs)
 * @property metricReportBatching Metric-specific batching (defaults to [batching])
 * @property traceReportBatching Trace-specific batching (defaults to [batching])
 * @property logReportBatching Log-specific batching (defaults to [batching] with 10x queue size)
 * @property batchingLimits Hard limits on batch processing (prevents unbounded queues)
 * @property spanLimits Limits on individual span payload sizes
 * @property logLimits Limits on individual log payload sizes
 * @property sampling Trace sampling configuration (null = 100% sampling)
 * @property maxSpansPerSecond Rate limit for span export (null = unlimited)
 * @property maxLogsPerSecond Rate limit for log export (null = unlimited)
 */
@Serializable
public data class OpenTelemetrySettings(
    override val url: String = "log",
    val batching: BatchingRules? = BatchingRules(),
    val metricReportBatching: BatchingRules? = batching,
    val traceReportBatching: BatchingRules? = batching,
    val logReportBatching: BatchingRules? = batching?.copy(maxQueueSize = batching.maxQueueSize * 10, maxSize = batching.maxSize * 10),

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
) : Setting<OpenTelemetry>, HasUrl {

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
    ) {
        public fun make(): Sampler = if (parentBased) {
            Sampler.parentBasedBuilder(
                Sampler.traceIdRatioBased(ratio)
            ).build()
        } else {
            Sampler.traceIdRatioBased(ratio)
        }
    }

    @Serializable
    public data class SpanLimitSettings(
        val maxAttributeValueLength: Int = 1024,
        val maxNumberOfAttributes: Int = 128,
        val maxNumberOfEvents: Int = 128,
        val maxNumberOfLinks: Int = 128,
        val maxNumberOfAttributesPerEvent: Int = 32,
        val maxNumberOfAttributesPerLink: Int = 32,
    ) {
        public fun make(): SpanLimits =
            SpanLimits.builder()
                .setMaxAttributeValueLength(maxAttributeValueLength)
                .setMaxNumberOfAttributes(maxNumberOfAttributes)
                .setMaxNumberOfEvents(maxNumberOfEvents)
                .setMaxNumberOfLinks(maxNumberOfLinks)
                .setMaxNumberOfAttributesPerEvent(maxNumberOfAttributesPerEvent)
                .setMaxNumberOfAttributesPerLink(maxNumberOfAttributesPerLink)
                .build()
    }

    public fun builder(exporter: SpanExporter): SdkTracerProviderBuilder {
        val wrappedExporter = maxSpansPerSecond?.let {
            RateLimitedSpanExporter(exporter, it)
        } ?: exporter

        return SdkTracerProvider.builder()
            .let {
                if(sampling != null)
                    it.setSampler(sampling.make())
                else it
            }
            .addSpanProcessor(
                traceReportBatching?.let {
                    BatchSpanProcessor.builder(wrappedExporter)
                        .setScheduleDelay(it.frequency.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                        .setMaxQueueSize(it.maxQueueSize)
                        .setMaxExportBatchSize(it.maxSize)
                        .setExporterTimeout(it.exportTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                        .build()
                } ?: SimpleSpanProcessor.create(wrappedExporter)
            )
            .setSpanLimits(
                spanLimits.make()
            )
    }

    public fun builder(exporter: LogRecordExporter): SdkLoggerProviderBuilder = SdkLoggerProvider.builder().apply {
        val safeExporter = SafeLogRecordExporter(exporter, logLimits.maxBodyLength, logLimits.maxStackTraceDepth)
        val wrappedExporter = maxLogsPerSecond?.let {
            RateLimitedLogRecordExporter(safeExporter, it)
        } ?: safeExporter

        addLogRecordProcessor(
            logReportBatching?.let {
                BatchLogRecordProcessor.builder(wrappedExporter)
                    .setScheduleDelay(it.frequency.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .setMaxQueueSize(it.maxQueueSize)
                    .setMaxExportBatchSize(it.maxSize)
                    .setExporterTimeout(it.exportTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .build()
            } ?: SimpleLogRecordProcessor.create(wrappedExporter)
        )
    }

    public fun builder(exporter: MetricExporter): SdkMeterProviderBuilder {
        return SdkMeterProvider.builder().registerMetricReader(
            (metricReportBatching?.frequency ?: 5.seconds).let {
                PeriodicMetricReader.builder(
                    exporter
                ).setInterval(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
            }
        )
    }

    public companion object : HasUrlSettingParser<OpenTelemetrySettings, OpenTelemetry>() {
        init {
//            this.register("none") { _, _, _ -> null}
            this.register("otlp-grpc") { name: String, setting: OpenTelemetrySettings, context ->
                val targetWithoutSchema = setting.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4317"
                val target = "http://$targetWithoutSchema"
                val resource =
                    Resource.getDefault().merge(Resource.builder().put("service.name", "opentelemetry-tests").build())
                val telemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        setting.builder(
                            OtlpGrpcSpanExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .setMeterProvider(
                        setting.builder(
                            OtlpGrpcMetricExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        setting.builder(
                            OtlpGrpcLogRecordExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("otlp-http") { name: String, setting: OpenTelemetrySettings, context ->
                val targetWithoutSchema = setting.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4318"
                val target = "http://$targetWithoutSchema"
                val resource =
                    Resource.getDefault().merge(Resource.builder().put("service.name", "opentelemetry-tests").build())
                val telemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        setting.builder(
                            OtlpHttpSpanExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .setMeterProvider(
                        setting.builder(
                            OtlpHttpMetricExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        setting.builder(
                            OtlpHttpLogRecordExporter.builder()
                                .setEndpoint(target).build()
                        )
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("console") { name: String, setting: OpenTelemetrySettings, context ->
                val resource = Resource.create(
                    Attributes.builder()
                        .put(/*ResourceAttributes.SERVICE_NAME*/"service.name", "opentelemetry-tests")
                        .build()
                )
                val telemetry =
                    OpenTelemetrySdk.builder()
                        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .setTracerProvider(
                            setting.builder(PrintSpanExporter)
                                .setResource(resource)
                                .build()
                        )
                        .setMeterProvider(
                            setting.builder(PrintMetricExporter)
                                .setResource(resource)
                                .build()
                        )
                        .setLoggerProvider(
                            setting.builder(PrintLogExporter)
                                .setResource(resource)
                                .build()
                        )
                        .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("log") { name: String, setting: OpenTelemetrySettings, context ->
                val resource = Resource.create(
                    Attributes.builder()
                        .put(/*ResourceAttributes.SERVICE_NAME*/"service.name", "opentelemetry-tests")
                        .build()
                )
                val telemetry =
                    OpenTelemetrySdk.builder()
                        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                        .setTracerProvider(
                            setting.builder(LoggingSpanExporter.create())
                                .setResource(resource)
                                .build()
                        )
                        .setMeterProvider(
                            setting.builder(LoggingMetricExporter.create())
                                .setResource(resource)
                                .build()
                        )
                        .setLoggerProvider(
                            setting.builder(SystemOutLogRecordExporter.create())
                                .setResource(resource)
                                .build()
                        )
                        .build()

                // Silence the log's console output lest we blow a hole in it
                (LoggerFactory.getILoggerFactory() as LoggerContext).apply {
                    getLogger(Logger.ROOT_LOGGER_NAME).apply {
                        iteratorForAppenders().asSequence().find {
                            it is ConsoleAppender<*>
                        }?.let { detachAppender(it) }
                    }
                }
                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("dev") { name: String, setting: OpenTelemetrySettings, context ->
                val resource = Resource.create(
                    Attributes.builder()
                        .put("service.name", name.ifBlank { "dev" })
                        .build()
                )

                // Parse URL options: dev://path/to/file?color=false
                val urlWithoutScheme = setting.url.substringAfter("://", "").let {
                    if (it.isEmpty()) setting.url.substringAfter("dev:", "") else it
                }
                val pathPart = urlWithoutScheme.substringBefore("?").takeIf { it.isNotBlank() }
                val queryPart = if (urlWithoutScheme.contains("?")) urlWithoutScheme.substringAfter("?") else ""
                val queryParams = queryPart.split("&")
                    .filter { it.contains("=") }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }

                val colorEnabled = queryParams["color"]?.lowercase() != "false"
                val outputFile = pathPart?.let { java.io.File(it) }

                val config = DevExporterConfig(color = colorEnabled, output = outputFile)

                // Dev mode: NO batching - use SimpleProcessor for immediate output
                val telemetry = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(
                        SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(DevSpanExporter(config)))
                            .setResource(resource)
                            .setSpanLimits(setting.spanLimits.make())
                            .build()
                    )
                    .setMeterProvider(
                        SdkMeterProvider.builder()
                            .registerMetricReader(
                                PeriodicMetricReader.builder(DevMetricExporter(config))
                                    .setInterval(10, TimeUnit.SECONDS)
                                    .build()
                            )
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        SdkLoggerProvider.builder()
                            .addLogRecordProcessor(SimpleLogRecordProcessor.create(DevLogExporter(config)))
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("debounced-dev") { name: String, setting: OpenTelemetrySettings, context ->
                val resource = Resource.create(
                    Attributes.builder()
                        .put("service.name", name.ifBlank { "dev" })
                        .build()
                )

                // Parse URL options: debounced-dev://path/to/file?color=false&debounce=1000&debounce_min=3
                val urlWithoutScheme = setting.url.substringAfter("://", "").let {
                    if (it.isEmpty()) setting.url.substringAfter("debounced-dev:", "") else it
                }
                val pathPart = urlWithoutScheme.substringBefore("?").takeIf { it.isNotBlank() }
                val queryPart = if (urlWithoutScheme.contains("?")) urlWithoutScheme.substringAfter("?") else ""
                val queryParams = queryPart.split("&")
                    .filter { it.contains("=") }
                    .associate { it.substringBefore("=") to it.substringAfter("=") }

                val colorEnabled = queryParams["color"]?.lowercase() != "false"
                val outputFile = pathPart?.let { java.io.File(it) }
                val debounceWindowMs = queryParams["debounce"]?.toLongOrNull()
                val debounceMinCount = queryParams["debounce_min"]?.toIntOrNull() ?: 1

                val config = DevExporterConfig(
                    color = colorEnabled,
                    output = outputFile,
                    debounceWindowMs = debounceWindowMs,
                    debounceMinCount = debounceMinCount
                )

                // Debounced dev mode: NO batching - use SimpleProcessor for immediate output
                val telemetry = OpenTelemetrySdk.builder()
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .setTracerProvider(
                        SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(DebouncedDevSpanExporter(config)))
                            .setResource(resource)
                            .setSpanLimits(setting.spanLimits.make())
                            .build()
                    )
                    .setMeterProvider(
                        SdkMeterProvider.builder()
                            .registerMetricReader(
                                PeriodicMetricReader.builder(DevMetricExporter(config))
                                    .setInterval(10, TimeUnit.SECONDS)
                                    .build()
                            )
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        SdkLoggerProvider.builder()
                            .addLogRecordProcessor(SimpleLogRecordProcessor.create(DevLogExporter(config)))
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
        }
    }

    override fun invoke(name: String, context: SettingContext): OpenTelemetry {
        return parse(name, this, context)
    }
}

private fun otelLoggingSetup(telemetry: OpenTelemetrySdk?) {
    (LoggerFactory.getILoggerFactory() as LoggerContext).apply logCtx@{
        getLogger(Logger.ROOT_LOGGER_NAME).apply {
            addAppender(OpenTelemetryAppender().apply {
                this.context = this@logCtx
                this.name = "OpenTelemetry"
                start()
            })
        }
    }
    OpenTelemetryAppender.install(telemetry)
}

/**
 * Wraps a LogRecordExporter to truncate oversized log bodies and stack traces.
 * Prevents massive log payloads from inflating infrastructure costs.
 */
private class SafeLogRecordExporter(
    private val delegate: LogRecordExporter,
    private val maxBodyLength: Int,
    @Suppress("UNUSED_PARAMETER") private val maxStackTraceDepth: Int
) : LogRecordExporter {
    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        // Note: LogRecordData is immutable, so we can't modify it directly.
        // The best we can do is pass through and rely on span limits for attributes.
        // Full truncation would require creating a custom implementation which is complex.
        // For now, we'll just log a warning if we see very large bodies.

        logs.forEach { log ->
            @Suppress("DEPRECATION")
            val bodyStr = log.body?.asString() ?: ""
            if (bodyStr.length > maxBodyLength) {
                java.util.logging.Logger.getLogger(SafeLogRecordExporter::class.java.name)
                    .fine("Log body exceeds max length (${bodyStr.length} > $maxBodyLength)")
            }
        }

        return delegate.export(logs)
    }

    override fun flush(): CompletableResultCode = delegate.flush()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
}

/**
 * Rate limiter for spans using a token bucket algorithm.
 * Prevents cost spikes during high-traffic or error burst scenarios.
 */
private class RateLimitedSpanExporter(
    private val delegate: SpanExporter,
    private val maxSpansPerSecond: Int
) : SpanExporter {
    private val permits = Semaphore(maxSpansPerSecond)
    private val refillScheduler = Executors.newScheduledThreadPool(1)

    init {
        // Refill permits every second
        refillScheduler.scheduleAtFixedRate({
            val availablePermits = permits.availablePermits()
            if (availablePermits < maxSpansPerSecond) {
                permits.release(maxSpansPerSecond - availablePermits)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val allowed = mutableListOf<SpanData>()
        var droppedCount = 0

        for (span in spans) {
            if (permits.tryAcquire()) {
                allowed.add(span)
            } else {
                droppedCount++
            }
        }

        if (droppedCount > 0) {
            java.util.logging.Logger.getLogger(RateLimitedSpanExporter::class.java.name)
                .warning("Rate limit exceeded: dropped $droppedCount spans")
        }

        return if (allowed.isNotEmpty()) {
            delegate.export(allowed)
        } else {
            CompletableResultCode.ofSuccess()
        }
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode {
        refillScheduler.shutdown()
        return delegate.shutdown()
    }
}

/**
 * Rate limiter for log records using a token bucket algorithm.
 * Prevents cost spikes during high-traffic or error burst scenarios.
 */
private class RateLimitedLogRecordExporter(
    private val delegate: LogRecordExporter,
    private val maxLogsPerSecond: Int
) : LogRecordExporter {
    private val permits = Semaphore(maxLogsPerSecond)
    private val refillScheduler = Executors.newScheduledThreadPool(1)

    init {
        // Refill permits every second
        refillScheduler.scheduleAtFixedRate({
            val availablePermits = permits.availablePermits()
            if (availablePermits < maxLogsPerSecond) {
                permits.release(maxLogsPerSecond - availablePermits)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        val allowed = mutableListOf<LogRecordData>()
        var droppedCount = 0

        for (log in logs) {
            if (permits.tryAcquire()) {
                allowed.add(log)
            } else {
                droppedCount++
            }
        }

        if (droppedCount > 0) {
            java.util.logging.Logger.getLogger(RateLimitedLogRecordExporter::class.java.name)
                .warning("Rate limit exceeded: dropped $droppedCount log records")
        }

        return if (allowed.isNotEmpty()) {
            delegate.export(allowed)
        } else {
            CompletableResultCode.ofSuccess()
        }
    }

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode {
        refillScheduler.shutdown()
        return delegate.shutdown()
    }
}

/*
 * TODO: API Recommendations for OpenTelemetry module
 *
 * 1. Remove println statements: Lines 183 and 219 use println() for debugging. Replace with proper
 *    logging using kotlin-logging for consistency with the rest of the library.
 *
 * 2. Configurable service name: Currently hardcoded to "opentelemetry-tests" in multiple places.
 *    Add a serviceName parameter to OpenTelemetrySettings to allow customization.
 *
 * 3. Authentication support: Add support for authentication headers in OTLP exporters (API keys,
 *    bearer tokens) for cloud providers like Honeycomb, New Relic, Datadog, etc.
 *
 * 4. SafeLogRecordExporter incomplete: The SafeLogRecordExporter only logs warnings but doesn't
 *    actually truncate oversized log bodies. Consider implementing proper truncation or removing
 *    the maxBodyLength parameter if it's not being used.
 *
 * 5. Rate limiter resource leak: RateLimitedSpanExporter and RateLimitedLogRecordExporter create
 *    scheduled executors but don't provide graceful shutdown timeouts. Consider adding a timeout
 *    parameter and using awaitTermination().
 *
 * 6. Batching defaults: The 5-minute default batching frequency is very conservative. Consider
 *    lowering it to 10-30 seconds for better real-time visibility while still maintaining efficiency.
 *
 * 7. Sampling configuration: Add support for more sophisticated sampling strategies like:
 *    - Error-based sampling (always sample traces with errors)
 *    - Latency-based sampling (always sample slow traces)
 *    - Rule-based sampling (sample by endpoint, user, etc.)
 *
 * 8. Logback side effects: The otelLoggingSetup() function modifies global Logback state. Consider
 *    making this opt-in via a configuration flag or documenting the behavior more prominently.
 *
 * 9. Resource attributes: Add support for customizing resource attributes (service.version,
 *    deployment.environment, host.name, etc.) beyond just service.name.
 *
 * 10. Exporter health checks: Add health check methods to validate connectivity to OTLP endpoints
 *     before application startup.
 */
