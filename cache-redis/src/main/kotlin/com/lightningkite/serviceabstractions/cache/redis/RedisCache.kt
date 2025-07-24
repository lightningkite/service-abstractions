package com.lightningkite.serviceabstractions.cache.redis

import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.cache.Cache
import com.lightningkite.serviceabstractions.cache.MetricTrackingCache
import io.lettuce.core.RedisClient
import io.lettuce.core.SetArgs
import io.lettuce.core.api.reactive.RedisReactiveCommands
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import redis.embedded.RedisServer
import kotlin.time.Duration
import kotlin.time.toJavaDuration

public class RedisCache(public val lettuceClient: RedisClient, override val context: SettingContext) : MetricTrackingCache() {
    public val json: Json = Json { this.serializersModule = context.serializersModule }
    public companion object {
        init {
            Cache.Settings.register("redis-test") { url, context ->
                val redisServer = RedisServer.builder()
                    .port(6379)
                    .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                    .slaveOf("localhost", 6378)
                    .setting("daemonize no")
                    .setting("appendonly no")
                    .setting("maxmemory 128M")
                    .build()
                redisServer.start()
                RedisCache(RedisClient.create("redis://127.0.0.1:6378"), context)
            }
            Cache.Settings.register("redis") { url, context ->
                RedisCache(RedisClient.create(url), context)
            }
        }
    }

    public val lettuceConnection: RedisReactiveCommands<String, String> = lettuceClient.connect().reactive()
    override suspend fun <T> getInternal(key: String, serializer: KSerializer<T>): T? {
        return lettuceConnection.get(key).awaitFirstOrNull()?.let { json.decodeFromString(serializer, it) }
    }

    override suspend fun <T> setInternal(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        lettuceConnection.set(
            key,
            json.encodeToString(serializer, value),
            SetArgs().let { timeToLive?.inWholeMilliseconds?.let { t -> it.px(t) } ?: it }
        ).collect {}
    }

    override suspend fun <T> setIfNotExistsInternal(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        val result = lettuceConnection.setnx(key, json.encodeToString(serializer, value)).awaitFirst()
        if(result) timeToLive?.let { lettuceConnection.pexpire(key, it.toJavaDuration()).collect {  } }
        return result
    }

    override suspend fun addInternal(key: String, value: Int, timeToLive: Duration?) {
        lettuceConnection.incrby(key, value.toLong()).collect { }
        timeToLive?.let {
            lettuceConnection.pexpire(key, it.toJavaDuration()).collect {  }
        }
    }

    override suspend fun removeInternal(key: String) {
        lettuceConnection.del(key).collect { }
    }
}