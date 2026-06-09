package com.lightningkite.services.files

import com.lightningkite.services.Namespaced

/**
 * Android implementation of file operation tracing - no-op.
 *
 * OpenTelemetry is not currently supported on Android platforms.
 */
internal actual suspend fun <T> traceFileOperation(
    owner: Namespaced,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any>,
    block: suspend () -> T,
): T {
    return block()
}
