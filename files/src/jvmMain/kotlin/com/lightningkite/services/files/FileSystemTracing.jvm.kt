package com.lightningkite.services.files

import com.lightningkite.services.SettingContext
import com.lightningkite.services.otel.TelemetrySanitization
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode

/**
 * JVM implementation of file operation tracing using OpenTelemetry.
 */
internal actual suspend fun <T> traceFileOperation(
    context: SettingContext,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any>,
    block: suspend () -> T
): T {
    val tracer = context.openTelemetry?.getTracer("files-kotlinxio")

    val span = tracer?.spanBuilder("file.$operation")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("file.operation", operation)
        ?.setAttribute("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(path))
        ?.setAttribute("storage.system", storageSystem)
        ?.apply {
            attributes.forEach { (key, value) ->
                when (value) {
                    is String -> setAttribute(key, value)
                    is Long -> setAttribute(key, value)
                    is Int -> setAttribute(key, value.toLong())
                    is Double -> setAttribute(key, value)
                    is Boolean -> setAttribute(key, value)
                }
            }
        }
        ?.startSpan()

    return try {
        val scope = span?.makeCurrent()
        try {
            val result = block()
            span?.setStatus(StatusCode.OK)
            result
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to $operation file: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}
