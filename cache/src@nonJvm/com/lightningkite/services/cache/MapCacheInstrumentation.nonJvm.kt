package com.lightningkite.services.cache

import kotlin.time.Duration

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedGetAndDelete(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = operation()
