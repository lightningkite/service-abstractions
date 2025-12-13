package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedGet(
    context: SettingContext,
    key: String,
    operation: suspend () -> T?
): T? = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedSet(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedSetIfNotExists(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedAdd(
    context: SettingContext,
    key: String,
    value: Int,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedRemove(
    context: SettingContext,
    key: String,
    operation: suspend () -> Unit
) = operation()

/**
 * Non-JVM implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedModify(
    context: SettingContext,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean = operation()
