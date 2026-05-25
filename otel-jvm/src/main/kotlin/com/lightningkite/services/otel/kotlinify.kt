package com.lightningkite.services.otel

import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.recordExceptionWithFingerprint
import io.opentelemetry.api.metrics.*
import io.opentelemetry.api.trace.*
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder


/**
 * Starts the span, makes it current for the block, ends it when the block returns or throws.
 *
 * Status is left as UNSET on success (per OTEL semantic conventions, UNSET represents successful
 * operation). The block may explicitly call `span.setStatus(StatusCode.OK)` or `setStatus(StatusCode.ERROR)`
 * if a non-default status is desired. On thrown exceptions the span is ended with status ERROR and
 * the exception recorded with a fingerprint. CancellationException is recorded as a "Cancelled" event.
 */
public inline fun <R> SpanBuilder.useBlocking(block: (span: Span) -> R): R {
    val span = startSpan()
    try {
        return span.makeCurrent().use {
            block(span)
        }
    } catch (t: CancellationException) {
        span.addEvent("Cancelled")
        throw t
    } catch (t: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordExceptionWithFingerprint(t)
        throw t
    } finally {
        span.end()
    }
}

/**
 * Suspend variant of [useBlocking]: starts the span, propagates it through the coroutine context,
 * ends it on completion. See [useBlocking] for status semantics.
 */
public suspend inline fun <R> SpanBuilder.use(crossinline block: suspend (span: Span) -> R): R {
    val span = startSpan()
    try {
        return withContext(span.asContextElement()) {
            block(span)
        }
    } catch (t: CancellationException) {
        span.addEvent("Cancelled")
        throw t
    } catch (t: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordExceptionWithFingerprint(t)
        throw t
    } finally {
        span.end()
    }
}

/**
 * Creates a span named [name] under this telemetry sub, configures it via [configure], and runs [block]
 * with the span made current for the coroutine. When the receiver is `null` (no telemetry configured)
 * the span is skipped and `null` is passed to [block].
 *
 * Status semantics: UNSET on success (block may override with `span?.setStatus(...)`), ERROR on thrown
 * exception with the exception recorded via [Span.recordExceptionWithFingerprint].
 */
public suspend inline fun <R> OpenTelemetrySub?.span(
    name: String,
    crossinline configure: SpanBuilder.() -> Unit = {},
    crossinline block: suspend (span: Span?) -> R,
): R {
    val sub = this ?: return block(null)
    return sub.spanBuilder(name).apply(configure).use { block(it) }
}

/**
 * Blocking variant of [span]: makes the span current via [Span.makeCurrent] instead of coroutine context.
 */
public inline fun <R> OpenTelemetrySub?.spanBlocking(
    name: String,
    configure: SpanBuilder.() -> Unit = {},
    block: (span: Span?) -> R,
): R {
    val sub = this ?: return block(null)
    return sub.spanBuilder(name).apply(configure).useBlocking { block(it) }
}

public operator fun OpenTelemetry.get(key: String): OpenTelemetrySub = OpenTelemetrySub(this, key)
public class OpenTelemetrySub(
    public val meter: Meter,
    public val tracer: Tracer,
    public val logger: Logger,
) : Tracer by tracer, Meter by meter, Logger by logger {
    public constructor(sdk: OpenTelemetry, key: String) : this(
        sdk.getMeter(key),
        sdk.getTracer(key),
        LoggerFactory.getLogger(key)
    )

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
        vararg additionalMeasurements: ObservableMeasurement?,
    ): BatchCallback? {
        return meter.batchCallback(callback, observableMeasurement, *additionalMeasurements)
    }
}