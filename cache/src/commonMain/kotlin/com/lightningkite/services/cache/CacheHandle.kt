package com.lightningkite.services.cache

import kotlinx.serialization.KSerializer
import kotlin.time.Duration

/**
 * A type-safe handle for a specific cache key with a fixed value type.
 *
 * This provides a cleaner API for repeated operations on the same key, eliminating
 * the need to specify the key and serializer on every operation.
 *
 * Typically created using the extension operator:
 * ```kotlin
 * val userCache: CacheHandle<User> = { cache }["user:123"]
 * userCache.set(user, timeToLive = 1.hours)
 * val cachedUser = userCache.get()
 * ```
 *
 * @param T The type of value stored under this key.
 * @property cache Lazy cache instance provider (allows reconfiguration between calls).
 * @property key The fixed cache key.
 * @property serializer Lazy serializer provider for type T.
 */
public class CacheHandle<T>(
    public val cache: () -> Cache,
    public val key: String,
    public val serializer: () -> KSerializer<T>
) {
    /** Retrieves the cached value, or null if absent/expired. */
    public suspend fun get(): T? = cache().get(key, serializer())

    /** Stores a value, overwriting any existing value. */
    public suspend fun set(value: T, timeToLive: Duration? = null): Unit =
        cache().set(key, value, serializer(), timeToLive)

    /** Stores a value only if the key doesn't exist. Returns true if stored, false if key existed. */
    public suspend fun setIfNotExists(value: T, timeToLive: Duration? = null): Boolean =
        cache().setIfNotExists(key, value, serializer(), timeToLive)

    /** Atomically modifies the cached value using compare-and-swap. See [Cache.modify] for details. */
    public suspend fun modify(
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?,
    ): Boolean = cache().modify(key, serializer(), maxTries, timeToLive, modification)

    /** Atomically increments a numeric value. See [Cache.add] for details. */
    public suspend fun add(value: Int, timeToLive: Duration? = null): Unit =
        cache().add(key, value, timeToLive)

    /** Removes this key from the cache. */
    public suspend fun remove(): Unit = cache().remove(key)
}
