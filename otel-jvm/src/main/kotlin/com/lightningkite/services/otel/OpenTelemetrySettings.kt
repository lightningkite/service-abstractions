package com.lightningkite.services.otel

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.ConsoleAppender
import com.lightningkite.services.HasUrl
import com.lightningkite.services.HasUrlSettingParser
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
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
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.slf4j.Logger
import java.util.concurrent.TimeUnit
import kotlin.apply
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
public data class OpenTelemetrySettings(
    override val url: String = "log",
    val reportFrequency: Duration? = 5.minutes,
    val metricReportFrequency: Duration? = reportFrequency,
    val traceReportFrequency: Duration? = reportFrequency,
    val logReportFrequency: Duration? = reportFrequency,
) : Setting<OpenTelemetry>, HasUrl {

    private fun builder(exporter: SpanExporter): SdkTracerProviderBuilder {
        return SdkTracerProvider.builder().addSpanProcessor(
            metricReportFrequency?.let {
                BatchSpanProcessor.builder(
                    exporter
                ).setScheduleDelay(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
            } ?: SimpleSpanProcessor.create(exporter)
        )
    }
    private fun builder(exporter: LogRecordExporter) = SdkLoggerProvider.builder().addLogRecordProcessor(
        metricReportFrequency?.let {
            BatchLogRecordProcessor.builder(
                exporter
            ).setScheduleDelay(it.inWholeMilliseconds, TimeUnit.MILLISECONDS).build()
        } ?: SimpleLogRecordProcessor.create(exporter)

    )
    private fun builder(exporter: MetricExporter): SdkMeterProviderBuilder {
        return SdkMeterProvider.builder().registerMetricReader(
            (metricReportFrequency ?: 5.seconds).let {
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
                val resource =
                    Resource.getDefault().merge(Resource.builder().put("service.name", "opentelemetry-tests").build())
                val telemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        setting.builder(OtlpGrpcSpanExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .setMeterProvider(
                        setting.builder(OtlpGrpcMetricExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        setting.builder(OtlpGrpcLogRecordExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("otlp-http") { name: String, setting: OpenTelemetrySettings, context ->
                val resource =
                    Resource.getDefault().merge(Resource.builder().put("service.name", "opentelemetry-tests").build())
                val telemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        setting.builder(OtlpHttpSpanExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .setMeterProvider(
                        setting.builder(OtlpHttpMetricExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .setLoggerProvider(
                        setting.builder(OtlpHttpLogRecordExporter.builder().setEndpoint(setting.url.removePrefix("oltp-grpc").let { "http$it" }).build())
                            .setResource(resource)
                            .build()
                    )
                    .build()

                otelLoggingSetup(telemetry)
                telemetry
            }
            this.register("print") { name: String, setting: OpenTelemetrySettings, context ->
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
    (LoggerFactory.getILoggerFactory() as LoggerContext).apply {
        getLogger(Logger.ROOT_LOGGER_NAME).apply {
            addAppender(OpenTelemetryAppender().apply {
                this.context = LoggerFactory.getILoggerFactory() as LoggerContext
                this.name = "OpenTelemetry"
                start()
            })
        }
    }
    OpenTelemetryAppender.install(telemetry)
}
