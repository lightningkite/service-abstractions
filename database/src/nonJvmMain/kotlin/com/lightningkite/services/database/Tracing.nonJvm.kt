package com.lightningkite.services.database

import com.lightningkite.services.OpenTelemetry

/**
 * Non-JVM implementation of tracing (no-op).
 *
 * Simply executes the block without creating any spans.
 */
internal actual suspend fun <R> traced(
    tracer: OpenTelemetry?,
    operation: String,
    tableName: String,
    attributes: Map<String, Any>,
    block: suspend () -> R
): R = block()
