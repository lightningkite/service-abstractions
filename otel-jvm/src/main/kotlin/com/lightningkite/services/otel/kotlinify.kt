package com.lightningkite.services.otel

import com.lightningkite.services.OpenTelemetry
import io.opentelemetry.api.metrics.BatchCallback
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.metrics.ObservableMeasurement
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder
import kotlin.coroutines.CoroutineContext


public inline fun <R> SpanBuilder.useBlocking(block: (span: Span) -> R) {
    val span = startSpan()
    try {
        return span.makeCurrent().use {
            val r = block(span)
            span.setStatus(StatusCode.OK)
            r
        }
    } catch(t: CancellationException) {
        span.addEvent("Cancelled")
        throw t
    } catch(t: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(t)
        throw t
    } finally {
        span.end()
    }
}
public suspend inline fun <R> SpanBuilder.use(crossinline block: suspend (span: Span) -> R) {
    val span = startSpan()
    try {
        return withContext(span.asContextElement()) {
            val r = block(span)
            span.setStatus(StatusCode.OK)
            r
        }
    } catch(t: CancellationException) {
        span.addEvent("Cancelled")
        throw t
    } catch(t: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(t)
        throw t
    } finally {
        span.end()
    }
}
public operator fun OpenTelemetry.get(key: String): OpenTelemetrySub = OpenTelemetrySub(this, key)
public class OpenTelemetrySub(
    public val meter: Meter,
    public val tracer: Tracer,
    public val logger: Logger,
): Tracer by tracer, Meter by meter, Logger by logger {
    public constructor(sdk: OpenTelemetry, key: String): this(sdk.getMeter(key), sdk.getTracer(key), LoggerFactory.getLogger(key))

    override fun makeLoggingEventBuilder(level: Level?): LoggingEventBuilder? {
        return logger.makeLoggingEventBuilder(level)
    }

    override fun atLevel(level: Level?): LoggingEventBuilder? {
        return logger.atLevel(level)
    }

    override fun isEnabledForLevel(level: Level?): Boolean {
        return logger.isEnabledForLevel(level)
    }

    override fun atTrace(): LoggingEventBuilder? {
        return logger.atTrace()
    }

    override fun atDebug(): LoggingEventBuilder? {
        return logger.atDebug()
    }

    override fun atInfo(): LoggingEventBuilder? {
        return logger.atInfo()
    }

    override fun atWarn(): LoggingEventBuilder? {
        return logger.atWarn()
    }

    override fun atError(): LoggingEventBuilder? {
        return logger.atError()
    }

    override fun batchCallback(
        callback: Runnable,
        observableMeasurement: ObservableMeasurement,
        vararg additionalMeasurements: ObservableMeasurement?
    ): BatchCallback? {
        return meter.batchCallback(callback, observableMeasurement, *additionalMeasurements)
    }
}