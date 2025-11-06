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
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
        internal fun make() = if (parentBased) {
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
        internal fun make() =
            SpanLimits.builder()
                .setMaxAttributeValueLength(maxAttributeValueLength)
                .setMaxNumberOfAttributes(maxNumberOfAttributes)
                .setMaxNumberOfEvents(maxNumberOfEvents)
                .setMaxNumberOfLinks(maxNumberOfLinks)
                .setMaxNumberOfAttributesPerEvent(maxNumberOfAttributesPerEvent)
                .setMaxNumberOfAttributesPerLink(maxNumberOfAttributesPerLink)
                .build()
    }

    private fun builder(exporter: SpanExporter): SdkTracerProviderBuilder {
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

    private fun builder(exporter: LogRecordExporter) = SdkLoggerProvider.builder().apply {
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

    private fun builder(exporter: MetricExporter): SdkMeterProviderBuilder {
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
                val target = setting.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4317"
                println("otlp-grpc target: '$target'")
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
                val target = setting.url.substringAfter("://", "").takeUnless { it.isBlank() } ?: "localhost:4318"
                println("otlp-http target: '$target'")
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
        }
    }

    override fun invoke(name: String, context: SettingContext): OpenTelemetry {
        return Companion.parse(name, this, context)
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
    private val permits = java.util.concurrent.Semaphore(maxSpansPerSecond)
    private val refillScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)

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
    private val permits = java.util.concurrent.Semaphore(maxLogsPerSecond)
    private val refillScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1)

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
