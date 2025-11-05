package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import com.lightningkite.services.default
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration

/**
 * An in-memory cache implementation backed by a [MutableMap].
 *
 * This implementation stores values in memory with optional TTL support. Choose a map implementation
 * that matches your concurrency needs:
 * - [ConcurrentHashMap] for thread-safe concurrent access (JVM default for "ram")
 * - Plain [MutableMap] for single-threaded use ("ram-unsafe")
 *
 * **Thread Safety:** Depends entirely on the provided map implementation. The "ram" URL scheme uses
 * [ConcurrentHashMap] on JVM, but "ram-unsafe" uses a plain map without synchronization.
 *
 * **Expiration:** Expired entries are checked on access but not proactively removed, so memory
 * usage can grow if expired keys aren't accessed. Consider periodic cleanup for long-running applications.
 *
 * @property entries The backing map storing cache entries. Must handle concurrent access if used from multiple threads.
 */
public open class MapCache(
    override val name: String,
    public val entries: MutableMap<String, Entry>,
    override val context: SettingContext,
) : Cache {
    private val serializersModule: SerializersModule get() = context.internalSerializersModule

    /**
     * A cache entry with optional expiration.
     * @property value The cached value (untyped for storage efficiency).
     * @property expires The instant when this entry expires, or null for no expiration.
     */
    public data class Entry(val value: Any?, val expires: Instant? = null)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }?.value as? T
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
    }

    /**
     * Removes all entries from the cache.
     *
     * **Warning:** This method is not part of the Cache interface and won't be available
     * through polymorphic references. Consider if this should be part of the Cache API.
     */
    public fun clear() {
        entries.clear()
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        val existing = entries[key]
        // Check if key doesn't exist OR if it exists but has expired
        if (existing == null || (existing.expires != null && existing.expires <= Clock.default().now())) {
            entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
            return true
        }
        return false
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Unit {
        val entry = entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }
        val current = entry?.value
        val new = when (current) {
            is Byte -> (current + value).toByte()
            is Short -> (current + value).toShort()
            is Int -> (current + value)
            is Long -> (current + value)
            is Float -> (current + value)
            is Double -> (current + value)
            else -> value
        }
        entries[key] = Entry(new, timeToLive?.let { Clock.default().now() + it })
    }

    override suspend fun remove(key: String): Unit {
        entries.remove(key)
    }

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?
    ): Boolean {
        // For MapCache, we can provide a synchronized implementation
        // Note: This works for in-memory but isn't distributed-safe
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)

            // Simple check - not perfect but better than nothing for single-process scenarios
            if (current == get(key, serializer)) {
                if (new != null) {
                    set(key, new, serializer, timeToLive)
                } else {
                    remove(key)
                }
                return true
            }
        }
        return false
    }
}

/*
 * TODO: API Recommendations for MapCache:
 *
 * 1. The `clear()` method is useful but not part of the Cache interface. Consider adding it to
 *    the Cache interface for consistency across implementations, or document that it's a
 *    MapCache-specific utility.
 *
 * 2. Memory leak concern: Expired entries are not proactively removed, only filtered on access.
 *    Consider adding:
 *    - A `cleanupExpired()` method to manually remove expired entries
 *    - Optional automatic cleanup on a schedule (e.g., every N operations)
 *    - A size limit with LRU eviction
 *
 * 3. The `serializersModule` property is unused - the serializer parameter is passed but never used
 *    for actual serialization. This works because values are stored as `Any?`, but it's inconsistent
 *    with the interface contract. Consider if MapCache should actually serialize values or document
 *    why it doesn't.
 *
 * 4. Thread safety: The class doesn't enforce or document thread-safety requirements for the backing map.
 *    While the documentation mentions choosing an appropriate map, consider:
 *    - Providing factory methods: `MapCache.concurrent()`, `MapCache.unsafe()`
 *    - Validating the map type at construction time
 *    - Adding explicit `@ThreadSafe` / `@NotThreadSafe` annotations
 */
