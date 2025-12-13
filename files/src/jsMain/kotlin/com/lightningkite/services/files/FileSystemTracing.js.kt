package com.lightningkite.services.files

import com.lightningkite.services.SettingContext

/**
 * JS implementation of file operation tracing - no-op.
 *
 * OpenTelemetry is not currently supported on JS platforms.
 */
internal actual suspend fun <T> traceFileOperation(
    context: SettingContext,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any>,
    block: suspend () -> T
): T {
    return block()
}
