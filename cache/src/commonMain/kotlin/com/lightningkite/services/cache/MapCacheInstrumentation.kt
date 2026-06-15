package com.lightningkite.services.cache

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes a cache get operation with optional telemetry instrumentation.
 *
 * On JVM with a telemetry backend configured, this opens a [com.lightningkite.services.telemetry.telemetryTrace]
 * span on [owner] to trace the operation. On other platforms or without telemetry, it directly
 * executes the operation. [owner] is the cache the operation belongs to, used as the span's owner.
 */
internal expect suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T?

/**
 * Executes a cache set operation with optional telemetry instrumentation.
 */
internal expect suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
)

/**
 * Executes a cache setIfNotExists operation with optional telemetry instrumentation.
 */
internal expect suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean

/**
 * Executes a cache add operation with optional telemetry instrumentation.
 */
internal expect suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
): N

/**
 * Executes a cache remove operation with optional telemetry instrumentation.
 */
internal expect suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
)

/**
 * Executes a cache modify operation with optional telemetry instrumentation.
 */
internal expect suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean

/**
 * Executes a cache getAndDelete operation with optional telemetry instrumentation.
 */
internal expect suspend fun <T> instrumentedGetAndDelete(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T?

internal fun assertValidTtl(timeToLive: Duration?) {
    if (timeToLive != null && timeToLive <= 0L.milliseconds || timeToLive == Duration.INFINITE)
        throw IllegalArgumentException("Invalid timeToLive. It must be at least 1 millisecond and not INFINITE")
}
