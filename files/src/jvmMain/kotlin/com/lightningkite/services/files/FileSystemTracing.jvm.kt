package com.lightningkite.services.files

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricKey
import com.lightningkite.services.MetricKeys
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
    val spanAttributes = MetricAttributes {
        put(MetricKeys.File.path, owner.context.telemetrySanitization.sanitizeFilePathWithDepth(path))
        put(MetricKey.OfString("storage.system"), storageSystem)
        attributes.forEach { (k, v) ->
            when (v) {
                is String  -> put(MetricKey.OfString(k), v)
                is Long    -> put(MetricKey.OfLong(k), v)
                is Int     -> put(MetricKey.OfLong(k), v.toLong())
                is Double  -> put(MetricKey.OfDouble(k), v)
                is Float   -> put(MetricKey.OfDouble(k), v.toDouble())
                is Boolean -> put(MetricKey.OfBoolean(k), v)
                else       -> put(MetricKey.OfString(k), v.toString())
            }
        }
    }
    return owner.metricsTrace(operation, attributes = spanAttributes) { block() }
}
