package com.lightningkite.services.files

import com.lightningkite.services.Namespaced

/**
 * Internal tracing helper for file operations.
 *
 * This provides telemetry tracing on JVM (via [com.lightningkite.services.metricsTrace] on [owner])
 * and no-op behavior on other platforms. [owner] is the file system the operation belongs to, used
 * as the span's owner.
 */
internal expect suspend fun <T> traceFileOperation(
    owner: Namespaced,
    operation: String,
    path: String,
    storageSystem: String,
    attributes: Map<String, Any> = emptyMap(),
    block: suspend () -> T,
): T
