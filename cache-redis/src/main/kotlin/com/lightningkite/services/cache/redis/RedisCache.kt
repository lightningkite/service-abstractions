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
}