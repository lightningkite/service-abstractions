package com.lightningkite.services.cache

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.MetricKey
import com.lightningkite.services.MetricKeys
import com.lightningkite.services.Service
import com.lightningkite.services.metricsTrace
import kotlin.time.Duration

/*
 * JVM implementation - wraps each cache operation in a metricsTrace span owned by the cache, so the
 * in-memory caches emit the same RED metrics and traces as the networked implementations. The cache
 * key is hashed via TelemetrySanitization so a high-cardinality value never reaches telemetry.
 */

private const val SYSTEM = "memory"

private fun Service.keyAttributes(key: String): MetricAttributes = MetricAttributes {
    put(Cache.MetricKeys.system, SYSTEM)
    put(Cache.MetricKeys.key, context.telemetrySanitization.hashCacheKey(key))
}

private fun Service.keyAttributes(key: String, timeToLive: Duration?, cacheValue: Long? = null): MetricAttributes = MetricAttributes {
    put(Cache.MetricKeys.system, SYSTEM)
    put(Cache.MetricKeys.key, context.telemetrySanitization.hashCacheKey(key))
    timeToLive?.let { put(Cache.MetricKeys.ttl, it.inWholeSeconds) }
    cacheValue?.let { put(Cache.MetricKeys.value, it) }
}

internal actual suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = owner.metricsTrace("get", attributes = owner.keyAttributes(key), dimensions = setOf(Cache.MetricKeys.hit)) { span ->
    val result = operation()
    span.enrich(MetricAttributes { put(Cache.MetricKeys.hit, result != null) })
    result
}

internal actual suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
) {
    owner.metricsTrace("set", attributes = owner.keyAttributes(key, timeToLive)) { operation() }
}

internal actual suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.metricsTrace("setIfNotExists", attributes = owner.keyAttributes(key, timeToLive)) { span ->
    val result = operation()
    span.enrich(MetricAttributes { put(Cache.MetricKeys.added, result) })
    result
}

internal actual suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
): N = owner.metricsTrace("add", attributes = owner.keyAttributes(key, timeToLive, value)) {
    operation()
}

internal actual suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
) {
    owner.metricsTrace("remove", attributes = owner.keyAttributes(key)) { operation() }
}

internal actual suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.metricsTrace(
    "modify",
    attributes = MetricAttributes { putAll(owner.keyAttributes(key, timeToLive)); put(MetricKey.OfLong("cache.maxTries"), maxTries.toLong()) },
) { operation() }
