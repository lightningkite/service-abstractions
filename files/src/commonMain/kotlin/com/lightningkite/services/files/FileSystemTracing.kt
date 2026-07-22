package com.lightningkite.services.files

import com.lightningkite.services.Namespaced
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryAttributes.Companion.invoke
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace

/**
 * Internal tracing helper for file operations.
 *
 * This provides telemetry tracing on JVM (via [com.lightningkite.services.telemetry.telemetryTrace] on [owner])
 * and no-op behavior on other platforms. [owner] is the file system the operation belongs to, used
 * as the span's owner.
 */
internal suspend fun <T> traceFileOperation(
    owner: Namespaced,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any> = emptyMap(),
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

