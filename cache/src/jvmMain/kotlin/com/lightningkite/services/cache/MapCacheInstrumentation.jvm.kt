package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import com.lightningkite.services.otel.TelemetrySanitization
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun <T> instrumentedGet(
    context: SettingContext,
    key: String,
    operation: suspend () -> T?
): T? {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.get")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "get")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.startSpan()

    return try {
        val scope = span?.makeCurrent()
        try {
            val result = operation()
            span?.setAttribute("cache.hit", result != null)
            span?.setStatus(StatusCode.OK)
            result
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to get from cache: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun <T> instrumentedSet(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.set")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "set")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.also { timeToLive?.let { ttl -> it.setAttribute("cache.ttl", ttl.inWholeSeconds) } }
        ?.startSpan()

    try {
        val scope = span?.makeCurrent()
        try {
            operation()
            span?.setStatus(StatusCode.OK)
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to set cache value: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun instrumentedSetIfNotExists(
    context: SettingContext,
    key: String,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.setIfNotExists")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "setIfNotExists")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.also { timeToLive?.let { ttl -> it.setAttribute("cache.ttl", ttl.inWholeSeconds) } }
        ?.startSpan()

    return try {
        val scope = span?.makeCurrent()
        try {
            val result = operation()
            span?.setAttribute("cache.added", result)
            span?.setStatus(StatusCode.OK)
            result
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to setIfNotExists: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun instrumentedAdd(
    context: SettingContext,
    key: String,
    value: Int,
    timeToLive: Duration?,
    operation: suspend () -> Unit
) {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.add")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "add")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.setAttribute("cache.value", value.toLong())
        ?.also { timeToLive?.let { ttl -> it.setAttribute("cache.ttl", ttl.inWholeSeconds) } }
        ?.startSpan()

    try {
        val scope = span?.makeCurrent()
        try {
            operation()
            span?.setStatus(StatusCode.OK)
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to add to cache: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun instrumentedRemove(
    context: SettingContext,
    key: String,
    operation: suspend () -> Unit
) {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.remove")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "remove")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.startSpan()

    try {
        val scope = span?.makeCurrent()
        try {
            operation()
            span?.setStatus(StatusCode.OK)
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to remove from cache: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}

/**
 * JVM implementation - creates OpenTelemetry spans for cache operations.
 */
internal actual suspend fun <T> instrumentedModify(
    context: SettingContext,
    key: String,
    maxTries: Int,
    timeToLive: Duration?,
    operation: suspend () -> Boolean
): Boolean {
    val tracer: Tracer? = context.openTelemetry?.getTracer("cache-map")
    val span = tracer?.spanBuilder("cache.modify")
        ?.setSpanKind(SpanKind.CLIENT)
        ?.setAttribute("cache.operation", "modify")
        ?.setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
        ?.setAttribute("cache.system", "memory")
        ?.setAttribute("cache.maxTries", maxTries.toLong())
        ?.also { timeToLive?.let { ttl -> it.setAttribute("cache.ttl", ttl.inWholeSeconds) } }
        ?.startSpan()

    return try {
        val scope = span?.makeCurrent()
        try {
            val result = operation()
            span?.setStatus(StatusCode.OK)
            result
        } finally {
            scope?.close()
        }
    } catch (e: Exception) {
        span?.setStatus(StatusCode.ERROR, "Failed to modify cache value: ${e.message}")
        span?.recordException(e)
        throw e
    } finally {
        span?.end()
    }
}
