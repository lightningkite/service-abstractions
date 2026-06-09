package com.lightningkite.services.files

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.Namespaced
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.TelemetrySanitization

/**
 * JVM implementation of file operation tracing using the coroutine-first metrics API. Opens a
 * [metricsTrace] span owned by [owner], named for the operation, carrying the sanitized path and the
 * caller-supplied attributes. The file path is sanitized so directory structure never reaches
 * telemetry.
 */
internal actual suspend fun <T> traceFileOperation(
    owner: Namespaced,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any>,
    block: suspend () -> T,
): T {
    val spanAttributes = MetricAttributes(
        buildMap {
            put("file.path", TelemetrySanitization.sanitizeFilePathWithDepth(path))
            put("storage.system", storageSystem)
            putAll(attributes)
        }
    )
    return owner.metricsTrace(operation, attributes = spanAttributes) { block() }
}
