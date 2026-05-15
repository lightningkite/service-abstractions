package com.lightningkite.services.cache.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.otel.OpenTelemetrySub
import com.lightningkite.services.otel.TelemetrySanitization
import com.lightningkite.services.otel.get
import com.lightningkite.services.otel.span
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry
import kotlinx.coroutines.reactive.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Redis implementation of the Cache abstraction using Lettuce (reactive client).
 *
 * Provides distributed caching with:
 * - **Atomic operations**: True CAS via Lua scripts
 * - **TTL support**: Native Redis expiration
 * - **High performance**: Reactive/non-blocking via Lettuce
 * - **Cluster support**: Works with Redis Cluster
 * - **Persistence options**: RDB/AOF configurable on Redis server
 *
 * ## Supported URL Schemes
 *
 * Standard Redis URLs (Lettuce format):
 * - `redis://localhost:6379` - Local Redis
 * - `redis://localhost:6379/0` - Specific database number
 * - `redis://user:password@host:6379` - Authenticated
 * - `rediss://host:6379` - TLS/SSL connection
 * - `redis-sentinel://host1:26379,host2:26379/mymaster` - Sentinel setup
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Local development
 * Cache.Settings("redis://localhost:6379")
 *
 * // Production with auth
 * Cache.Settings("redis://user:pass@cache.example.com:6379")
 *
 * // ElastiCache/Redis Cloud
 * Cache.Settings("redis://my-cluster.cache.amazonaws.com:6379")
 *
 * // With TLS
 * Cache.Settings("rediss://secure-cache.example.com:6380")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Serialization**: Values stored as JSON strings
 * - **Compare-and-set**: Lua script for true atomicity (not WATCH/MULTI)
 * - **Reactive**: Uses Lettuce reactive API for non-blocking operations
 * - **Connection pooling**: Managed by Lettuce client
 *
 * @property name Service name for logging/metrics
 * @property lettuceClient Lettuce Redis client instance
 * @property context Service context with serializers
 */
public class RedisCache(
    override val name: String,
    public val lettuceClient: RedisClient,
    override val context: SettingContext,
) : Cache {
    public val json: Json = Json { this.serializersModule = context.internalSerializersModule }

    private val otel: OpenTelemetrySub? = context.openTelemetry?.get("cache-redis")

    public companion object {
        public fun Cache.Settings.Companion.redis(url: String): Cache.Settings = Cache.Settings("redis://$url")

        // Lua script: atomic INCRBY + conditional PEXPIRE.
        // KEYS[1] = key, ARGV[1] = delta, ARGV[2] = ttl ms (or "" to skip)
        internal const val LUA_ADD: String = """
local v = redis.call('INCRBY', KEYS[1], ARGV[1])
if ARGV[2] ~= '' then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return v
"""

        // Lua scripts for compareAndSet — defined here so the SHA can be cached per-class.
        internal const val LUA_CAS_INSERT: String = """
if redis.call('EXISTS', KEYS[1]) == 0 then
    if ARGV[2] ~= '' then
        redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
    else
        redis.call('SET', KEYS[1], ARGV[1])
    end
    return 1
else
    return 0
end
"""

        internal const val LUA_CAS_DELETE: String = """
local current = redis.call('GET', KEYS[1])
if current == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
else
    return 0
end
"""

        internal const val LUA_CAS_UPDATE: String = """
local current = redis.call('GET', KEYS[1])
if current == ARGV[1] then
    if ARGV[3] ~= '' then
        redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2])
    else
        redis.call('SET', KEYS[1], ARGV[2])
    end
    return 1
else
    return 0
end
"""

        init {
            Cache.Settings.register("redis") { name, url, context ->
                val telemetry = context.openTelemetry?.let { LettuceTelemetry.create(it) }
                val clientResources = telemetry?.let {
                    ClientResources.builder()
                        .tracing(it.newTracing())
                        .build()
                } ?: ClientResources.create()

                val client = RedisClient.create(clientResources, url)
                RedisCache(name, client, context)
            }
        }
    }

    // Cached SHAs for each Lua script — null means not yet loaded.
    private val shaAdd = AtomicReference<String?>(null)
    private val shaCasInsert = AtomicReference<String?>(null)
    private val shaCasDelete = AtomicReference<String?>(null)
    private val shaCasUpdate = AtomicReference<String?>(null)

    /**
     * Calls EVALSHA for [src]; on NOSCRIPT reloads the script and retries once.
     * This avoids a round-trip SCRIPT LOAD on every call after the first successful load.
     */
    private suspend fun <T> eval(
        shaRef: AtomicReference<String?>,
        src: String,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T? {
        // Load script if SHA not cached yet.
        val sha = shaRef.get() ?: lettuceConnection.scriptLoad(src).awaitFirst().also { shaRef.set(it) }
        return try {
            lettuceConnection.evalsha<T>(sha, outputType, keys, *args).awaitFirstOrNull()
        } catch (e: RedisNoScriptException) {
            // Script was flushed from Redis; reload and retry once.
            val newSha = lettuceConnection.scriptLoad(src).awaitFirst()
            shaRef.set(newSha)
            lettuceConnection.evalsha<T>(newSha, outputType, keys, *args).awaitFirstOrNull()
        }
    }

    public val lettuceConnection: RedisReactiveCommands<String, String> = lettuceClient.connect().reactive()

    private inline fun SpanBuilderAttrs(key: String): io.opentelemetry.api.trace.SpanBuilder.() -> Unit = {
        setSpanKind(SpanKind.CLIENT)
        setAttribute("cache.system", "redis")
        setAttribute("cache.key", TelemetrySanitization.hashCacheKey(key))
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        otel.span("cache.get", configure = SpanBuilderAttrs(key)) { span ->
            val result = lettuceConnection.get(key).awaitFirstOrNull()?.let { json.decodeFromString(serializer, it) }
            span?.setAttribute("cache.hit", result != null)
            result
        }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        otel.span("cache.set", configure = SpanBuilderAttrs(key)) {
            lettuceConnection.set(
                key,
                json.encodeToString(serializer, value),
                SetArgs().let { timeToLive?.inWholeMilliseconds?.let { t -> it.px(t) } ?: it }
            ).collect {}
        }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean = otel.span("cache.setIfNotExists", configure = SpanBuilderAttrs(key)) { span ->
        // Atomic SET key value NX PX ttl — avoids the non-atomic setnx+pexpire race.
        val args = SetArgs.Builder.nx().let { a ->
            timeToLive?.inWholeMilliseconds?.let { a.px(it) } ?: a
        }
        val result = lettuceConnection.set(key, json.encodeToString(serializer, value), args)
            .awaitFirstOrNull() != null
        span?.setAttribute("cache.added", result)
        result
    }

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        otel.span("cache.add", configure = SpanBuilderAttrs(key)) {
            // Lua script performs INCRBY + PEXPIRE atomically to avoid the race where
            // another caller modifies TTL between our INCRBY and PEXPIRE calls.
            val ttlArg = timeToLive?.inWholeMilliseconds?.toString() ?: ""
            eval<Long>(shaAdd, LUA_ADD, ScriptOutputType.INTEGER, arrayOf(key), value.toString(), ttlArg) ?: 0L
        }

    override suspend fun remove(key: String): Unit =
        otel.span("cache.remove", configure = SpanBuilderAttrs(key)) {
            lettuceConnection.del(key).collect { }
        }

    override suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration?,
    ): Boolean {
        // Early return if expected equals new
        if (expected == new) return true

        return otel.span("cache.compareAndSet", configure = SpanBuilderAttrs(key)) {
            val expectedJson = expected?.let { json.encodeToString(serializer, it) }
            val newJson = new?.let { json.encodeToString(serializer, it) }
            val ttlArg = timeToLive?.inWholeMilliseconds?.toString() ?: ""

            val result: Long? = when {
                expected == null -> eval(
                    shaCasInsert, LUA_CAS_INSERT, ScriptOutputType.INTEGER, arrayOf(key),
                    newJson!!, ttlArg,
                )
                new == null -> eval(
                    shaCasDelete, LUA_CAS_DELETE, ScriptOutputType.INTEGER, arrayOf(key),
                    expectedJson!!,
                )
                else -> eval(
                    shaCasUpdate, LUA_CAS_UPDATE, ScriptOutputType.INTEGER, arrayOf(key),
                    expectedJson!!, newJson!!, ttlArg,
                )
            }
            result == 1L
        }
    }

}