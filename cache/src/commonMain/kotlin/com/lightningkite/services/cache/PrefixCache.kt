package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

public class PrefixCache(public val cache: Cache, public val prefix: String) : Cache {
    override val name: String get() = cache.name
    override val context: SettingContext
        get() = cache.context

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = cache.get(prefix + key, serializer)
    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?): Unit =
        cache.set(prefix + key, value, serializer, timeToLive)

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean = cache.setIfNotExists(prefix + key, value, serializer, timeToLive)

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?,
    ): Boolean = cache.modify(prefix + key, serializer, maxTries, timeToLive, modification)

    override suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration?,
    ): Boolean = cache.compareAndSet(prefix + key, serializer, expected, new, timeToLive)

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        cache.add(prefix + key, value, timeToLive)

    // Without this, calls resolve to the interface's default `add(Int) = add(Long).toInt()`, which
    // routes through the Long overload above instead of the wrapped cache's own Int overload. For
    // backends that preserve the stored numeric type on a fresh key (e.g. MapCache), that silently
    // widens Int keys to Long, breaking `get<Int>()` round-trips (surfaced by PrefixCacheTest).
    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Int =
        cache.add(prefix + key, value, timeToLive)

    override suspend fun remove(key: String): Unit = cache.remove(prefix + key)
    override suspend fun <T> getAndRemove(key: String, serializer: KSerializer<T>): T? =
        cache.getAndRemove(prefix + key, serializer)
    override suspend fun healthCheck(): HealthStatus = cache.healthCheck()
}