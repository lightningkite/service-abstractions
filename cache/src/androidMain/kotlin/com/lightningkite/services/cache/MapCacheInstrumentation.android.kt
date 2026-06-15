package com.lightningkite.services.cache

import kotlin.time.Duration

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 *
 * Note: While Android is a JVM-based platform, OpenTelemetry support is JVM-only in this codebase.
 * If Android OpenTelemetry support is added in the future, this implementation can be updated.
 */
internal actual suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedGetAndDelete(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = operation()
