package com.lightningkite.services.database

import com.lightningkite.services.OpenTelemetry
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * JVM implementation of tracing using OpenTelemetry.
 *
 * Creates spans for database operations with appropriate attributes.
 */
internal actual suspend fun <R> traced(
    tracer: OpenTelemetry?,
    operation: String,
    tableName: String,
    attributes: Map<String, Any>,
    block: suspend () -> R
): R {
    if (tracer == null) {
        return block()
    }

    val otelTracer = tracer.getTracer("database-inmemory")
    val span = otelTracer.spanBuilder("memory.$operation")
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute("db.system", "memory")
        .setAttribute("db.operation", operation)
        .setAttribute("db.collection", tableName)
        .apply {
            attributes.forEach { (key, value) ->
                when (value) {
                    is String -> setAttribute(key, value)
                    is Long -> setAttribute(key, value)
                    is Int -> setAttribute(key, value.toLong())
                    is Boolean -> setAttribute(key, value)
                    is Double -> setAttribute(key, value)
                    else -> setAttribute(key, value.toString())
                }
            }
        }
        .startSpan()

    return try {
        withContext(span.asContextElement()) {
            val result = block()
            span.setStatus(StatusCode.OK)
            result
        }
    } catch (t: CancellationException) {
        span.addEvent("Cancelled")
        throw t
    } catch (t: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(t)
        throw t
    } finally {
        span.end()
    }
}
