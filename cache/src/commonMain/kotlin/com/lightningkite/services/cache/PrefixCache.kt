package com.lightningkite.services.cache

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

public class PrefixCache(public val cache: Cache, public val prefix: String): Cache {
    override val context: SettingContext
        get() = cache.context
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = cache.get(prefix + key, serializer)
    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit = cache.set(prefix + key, value, serializer, timeToLive)
    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean = cache.setIfNotExists(prefix + key, value, serializer, timeToLive)
    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?
    ): Boolean = cache.modify(prefix + key, serializer, maxTries, timeToLive, modification)
    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Unit = cache.add(prefix + key, value, timeToLive)
    override suspend fun remove(key: String): Unit = cache.remove(prefix + key)
    override suspend fun healthCheck(): HealthStatus = cache.healthCheck()
}