package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 *
 * Note: While Android is a JVM-based platform, OpenTelemetry support is JVM-only in this codebase.
 * If Android OpenTelemetry support is added in the future, this implementation can be updated.
 */
internal actual suspend fun <T> instrumentedGet(
    context: SettingContext,
    key: String,
    operation: suspend () -> T?
): T? = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedSet(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedSetIfNotExists(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedAdd(
    context: SettingContext,
    key: String,
    value: Int,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun instrumentedRemove(
    context: SettingContext,
    key: String,
    operation: suspend () -> Unit
) = operation()

/**
 * Android implementation - no-op, directly executes the operation without instrumentation.
 */
internal actual suspend fun <T> instrumentedModify(
    context: SettingContext,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean = operation()
