package com.lightningkite.services.database

import com.lightningkite.services.OpenTelemetry

/**
 * Platform-specific tracing support for database operations.
 *
 * On JVM, this creates OpenTelemetry spans. On other platforms, this is a no-op.
 */
internal expect suspend fun <R> traced(
    tracer: OpenTelemetry?,
    operation: String,
    tableName: String,
    attributes: Map<String, Any> = emptyMap(),
    block: suspend () -> R
): R
