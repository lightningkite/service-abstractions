package com.lightningkite.services.cache

import kotlinx.serialization.KSerializer
import kotlin.time.Duration

/**
 * A class that handles and manipulates a single key in a cache.
 */
public class CacheHandle<T>(public val cache: () -> Cache, public val key: String, public val serializer: ()->KSerializer<T>) {
    public suspend fun get(): T? = cache().get(key, serializer())
    public suspend fun set(value: T, timeToLive: Duration? = null): Unit = cache().set(key, value, serializer(), timeToLive)
    public suspend fun setIfNotExists(value: T, timeToLive: Duration? = null): Boolean =
        cache().setIfNotExists(key, value, serializer(), timeToLive)

    public suspend fun modify(
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?,
    ): Boolean = cache().modify(key, serializer(), maxTries, timeToLive, modification)

    public suspend fun add(value: Int, timeToLive: Duration? = null): Unit = cache().add(key, value, timeToLive)
    public suspend fun remove(): Unit = cache().remove(key)
}
