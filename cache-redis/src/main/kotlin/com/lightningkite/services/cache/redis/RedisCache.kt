package com.lightningkite.services.cache.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.toJavaDuration

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
    override val context: SettingContext
) : Cache {
    public val json: Json = Json { this.serializersModule = context.internalSerializersModule }

    public companion object {
        public fun Cache.Settings.Companion.redis(url: String): Cache.Settings = Cache.Settings("redis://$url")
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

    public val lettuceConnection: RedisReactiveCommands<String, String> = lettuceClient.connect().reactive()
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return lettuceConnection.get(key).awaitFirstOrNull()?.let { json.decodeFromString(serializer, it) }
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
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
        timeToLive: Duration?
    ): Boolean {
        val result = lettuceConnection.setnx(key, json.encodeToString(serializer, value)).awaitFirst()
        if (result) timeToLive?.let { lettuceConnection.pexpire(key, it.toJavaDuration()).collect { } }
        return result
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        lettuceConnection.incrby(key, value.toLong()).collect { }
        timeToLive?.let {
            lettuceConnection.pexpire(key, it.toJavaDuration()).collect { }
        }
    }

    override suspend fun remove(key: String) {
        lettuceConnection.del(key).collect { }
    }

    override suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration?
    ): Boolean {
        // Early return if expected equals new
        if (expected == new) return true

        // Use a Lua script for atomic compare-and-set
        // This is more reliable than WATCH/MULTI/EXEC in reactive contexts
        val expectedJson = expected?.let { json.encodeToString(serializer, it) }
        val newJson = new?.let { json.encodeToString(serializer, it) }

        val script = when {
            expected == null && new == null -> {
                // Both null - nothing to do
                return true
            }
            expected == null && new != null -> {
                // Insert if not exists
                """
                if redis.call('EXISTS', KEYS[1]) == 0 then
                    if ARGV[2] then
                        redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1])
                    else
                        redis.call('SET', KEYS[1], ARGV[1])
                    end
                    return 1
                else
                    return 0
                end
                """.trimIndent()
            }
            expected != null && new == null -> {
                // Delete if matches
                """
                local current = redis.call('GET', KEYS[1])
                if current == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    return 1
                else
                    return 0
                end
                """.trimIndent()
            }
            else -> {
                // Update if matches
                """
                local current = redis.call('GET', KEYS[1])
                if current == ARGV[1] then
                    if ARGV[3] then
                        redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2])
                    else
                        redis.call('SET', KEYS[1], ARGV[2])
                    end
                    return 1
                else
                    return 0
                end
                """.trimIndent()
            }
        }

        val args = when {
            expected == null && new != null -> {
                // Insert: ARGV[1] = newJson, ARGV[2] = ttl (optional)
                if (timeToLive != null) {
                    arrayOf(newJson!!, timeToLive.inWholeMilliseconds.toString())
                } else {
                    arrayOf(newJson!!)
                }
            }
            expected != null && new == null -> {
                // Delete: ARGV[1] = expectedJson
                arrayOf(expectedJson!!)
            }
            else -> {
                // Update: ARGV[1] = expectedJson, ARGV[2] = newJson, ARGV[3] = ttl (optional)
                if (timeToLive != null) {
                    arrayOf(expectedJson!!, newJson!!, timeToLive.inWholeMilliseconds.toString())
                } else {
                    arrayOf(expectedJson!!, newJson!!)
                }
            }
        }

        val result = lettuceConnection.eval<Long>(script, io.lettuce.core.ScriptOutputType.INTEGER, arrayOf(key), *args)
            .awaitFirstOrNull()

        return result == 1L
    }

}