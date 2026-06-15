package com.lightningkite.services.files

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.Namespaced
import com.lightningkite.services.telemetry.telemetryTrace

/**
 * JVM implementation of file operation tracing using the coroutine-first metrics API. Opens a
 * [telemetryTrace] span owned by [owner], named for the operation, carrying the sanitized path and the
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
    val spanAttributes = TelemetryAttributes {
        put(TelemetryKeys.File.path, owner.context.telemetrySanitization.sanitizeFilePathWithDepth(path))
        put(TelemetryKey.OfString("storage.system"), storageSystem)
        attributes.forEach { (k, v) ->
            when (v) {
                is String  -> put(TelemetryKey.OfString(k), v)
                is Long    -> put(TelemetryKey.OfLong(k), v)
                is Int     -> put(TelemetryKey.OfLong(k), v.toLong())
                is Double  -> put(TelemetryKey.OfDouble(k), v)
                is Float   -> put(TelemetryKey.OfDouble(k), v.toDouble())
                is Boolean -> put(TelemetryKey.OfBoolean(k), v)
                else       -> put(TelemetryKey.OfString(k), v.toString())
            }
        }
    }
    return owner.telemetryTrace(operation, attributes = spanAttributes) { block() }
}
