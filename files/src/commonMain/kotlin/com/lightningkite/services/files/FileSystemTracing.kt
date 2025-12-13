package com.lightningkite.services.files

import com.lightningkite.services.SettingContext

/**
 * Internal tracing helper for file operations.
 *
 * This provides OpenTelemetry tracing on JVM and no-op behavior on other platforms.
 */
internal expect suspend fun <T> traceFileOperation(
    context: SettingContext,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any> = emptyMap(),
    block: suspend () -> T
): T
