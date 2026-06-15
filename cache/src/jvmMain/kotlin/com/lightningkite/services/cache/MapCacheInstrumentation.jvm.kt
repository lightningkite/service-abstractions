package com.lightningkite.services.cache

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.Service
import com.lightningkite.services.telemetry.telemetryTrace
import kotlin.time.Duration

/*
 * JVM implementation - wraps each cache operation in a telemetryTrace span owned by the cache, so the
 * in-memory caches emit the same RED metrics and traces as the networked implementations. The cache
 * key is hashed via TelemetrySanitization so a high-cardinality value never reaches telemetry.
 */

private const val SYSTEM = "memory"

private fun Service.keyAttributes(key: String): TelemetryAttributes = TelemetryAttributes {
    put(Cache.TelemetryKeys.system, SYSTEM)
    put(Cache.TelemetryKeys.key, context.telemetrySanitization.hashCacheKey(key))
}

private fun Service.keyAttributes(key: String, timeToLive: Duration?, cacheValue: Long? = null): TelemetryAttributes = TelemetryAttributes {
    put(Cache.TelemetryKeys.system, SYSTEM)
    put(Cache.TelemetryKeys.key, context.telemetrySanitization.hashCacheKey(key))
    timeToLive?.let { put(Cache.TelemetryKeys.ttl, it.inWholeSeconds) }
    cacheValue?.let { put(Cache.TelemetryKeys.value, it) }
}

internal actual suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = owner.telemetryTrace("get", attributes = owner.keyAttributes(key), dimensions = setOf(Cache.TelemetryKeys.hit)) { span ->
    val result = operation()
    span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.hit, result != null) })
    result
}

internal actual suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
) {
    owner.telemetryTrace("set", attributes = owner.keyAttributes(key, timeToLive)) { operation() }
}

internal actual suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.telemetryTrace("setIfNotExists", attributes = owner.keyAttributes(key, timeToLive)) { span ->
    val result = operation()
    span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.added, result) })
    result
}

internal actual suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
): N = owner.telemetryTrace("add", attributes = owner.keyAttributes(key, timeToLive, value)) {
    operation()
}

internal actual suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
) {
    owner.telemetryTrace("remove", attributes = owner.keyAttributes(key)) { operation() }
}

internal actual suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.telemetryTrace(
    "modify",
    attributes = TelemetryAttributes { putAll(owner.keyAttributes(key, timeToLive)); put(TelemetryKey.OfLong("cache.maxTries"), maxTries.toLong()) },
) { operation() }

internal actual suspend fun <T> instrumentedGetAndDelete(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = owner.telemetryTrace("getAndDelete", attributes = owner.keyAttributes(key), dimensions = setOf(Cache.TelemetryKeys.hit)) { span ->
    val result = operation()
    span.enrich(TelemetryAttributes { put(Cache.TelemetryKeys.hit, result != null) })
    result
}
