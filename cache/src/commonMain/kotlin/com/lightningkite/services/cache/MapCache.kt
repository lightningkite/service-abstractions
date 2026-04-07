package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
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
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect class MapCache(
    name: String,
    context: SettingContext,
) : Cache {

    override val name: String
    override val context: SettingContext

    /**
     * A cache entry with optional expiration.
     * @property value The cached value (untyped for storage efficiency).
     * @property expires The instant when this entry expires, or null for no expiration.
     */
    public class Entry(value: Any?, expires: Instant? = null)

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T?

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?)

    /**
     * Removes all entries from the cache.
     *
     * **Warning:** This method is not part of the Cache interface and won't be available
     * through polymorphic references. Consider if this should be part of the Cache API.
     */
    public fun clear()

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean

    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Unit

    override suspend fun remove(key: String): Unit

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?
    ): Boolean
}
