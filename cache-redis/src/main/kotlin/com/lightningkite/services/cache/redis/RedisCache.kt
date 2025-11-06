package com.lightningkite.services.cache.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.cache.Cache
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.reactive.RedisReactiveCommands
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.toJavaDuration

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
                RedisCache(name, RedisClient.create(url), context)
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