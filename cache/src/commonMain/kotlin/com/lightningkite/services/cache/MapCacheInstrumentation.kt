package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes a cache get operation with optional OpenTelemetry instrumentation.
 *
 * On JVM with OpenTelemetry configured, this creates a span to trace the operation.
 * On other platforms or without telemetry, this directly executes the operation.
 */
internal expect suspend fun <T> instrumentedGet(
    context: SettingContext,
    key: String,
    operation: suspend () -> T?,
): T?

/**
 * Executes a cache set operation with optional OpenTelemetry instrumentation.
 */
internal expect suspend fun <T> instrumentedSet(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
)

/**
 * Executes a cache setIfNotExists operation with optional OpenTelemetry instrumentation.
 */
internal expect suspend fun instrumentedSetIfNotExists(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean

/**
 * Executes a cache add operation with optional OpenTelemetry instrumentation.
 */
internal expect suspend fun <N : Number> instrumentedAdd(
    context: SettingContext,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
): N

/**
 * Executes a cache remove operation with optional OpenTelemetry instrumentation.
 */
internal expect suspend fun instrumentedRemove(
    context: SettingContext,
    key: String,
    operation: suspend () -> Unit,
)

/**
 * Executes a cache modify operation with optional OpenTelemetry instrumentation.
 */
internal expect suspend fun <T> instrumentedModify(
    context: SettingContext,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean

internal fun assertValidTtl(timeToLive: Duration?) {
    if (timeToLive != null && timeToLive <= 0L.milliseconds || timeToLive == Duration.INFINITE)
        throw IllegalArgumentException("Invalid timeToLive. It must be at least 1 millisecond and not INFINITE")
}
