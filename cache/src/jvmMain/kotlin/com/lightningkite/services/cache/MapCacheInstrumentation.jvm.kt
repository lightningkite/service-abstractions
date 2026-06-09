package com.lightningkite.services.cache

import com.lightningkite.services.MetricAttributes
import com.lightningkite.services.metricsTrace
import com.lightningkite.services.TelemetrySanitization
import kotlin.time.Duration

/*
 * JVM implementation - wraps each cache operation in a metricsTrace span owned by the cache, so the
 * in-memory caches emit the same RED metrics and traces as the networked implementations. The cache
 * key is hashed via TelemetrySanitization so a high-cardinality value never reaches telemetry.
 */

private const val SYSTEM = "memory"

private fun keyAttributes(key: String): MetricAttributes = MetricAttributes(
    mapOf("cache.system" to SYSTEM, "cache.key" to TelemetrySanitization.hashCacheKey(key))
)

private fun keyAttributes(key: String, timeToLive: Duration?, extra: Map<String, Any?> = emptyMap()): MetricAttributes {
    val map = mutableMapOf<String, Any?>("cache.system" to SYSTEM, "cache.key" to TelemetrySanitization.hashCacheKey(key))
    timeToLive?.let { map["cache.ttl"] = it.inWholeSeconds }
    map.putAll(extra)
    return MetricAttributes(map)
}

internal actual suspend fun <T> instrumentedGet(
    owner: Cache,
    key: String,
    operation: suspend () -> T?,
): T? = owner.metricsTrace("get", attributes = keyAttributes(key), dimensions = setOf("cache.hit")) { span ->
    val result = operation()
    span.enrich(MetricAttributes(mapOf("cache.hit" to (result != null))))
    result
}

internal actual suspend fun <T> instrumentedSet(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit,
) {
    owner.metricsTrace("set", attributes = keyAttributes(key, timeToLive)) { operation() }
}

internal actual suspend fun instrumentedSetIfNotExists(
    owner: Cache,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.metricsTrace("setIfNotExists", attributes = keyAttributes(key, timeToLive)) { span ->
    val result = operation()
    span.enrich(MetricAttributes(mapOf("cache.added" to result)))
    result
}

internal actual suspend fun <N : Number> instrumentedAdd(
    owner: Cache,
    key: String,
    value: Long,
    timeToLive: Duration?,
    operation: suspend () -> N,
): N = owner.metricsTrace("add", attributes = keyAttributes(key, timeToLive, mapOf("cache.value" to value))) {
    operation()
}

internal actual suspend fun instrumentedRemove(
    owner: Cache,
    key: String,
    operation: suspend () -> Unit,
) {
    owner.metricsTrace("remove", attributes = keyAttributes(key)) { operation() }
}

internal actual suspend fun <T> instrumentedModify(
    owner: Cache,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean,
): Boolean = owner.metricsTrace(
    "modify",
    attributes = keyAttributes(key, timeToLive, mapOf("cache.maxTries" to maxTries.toLong())),
) { operation() }
