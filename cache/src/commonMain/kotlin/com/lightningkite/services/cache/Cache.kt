package com.lightningkite.services.cache

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An abstraction for caching data in external storage systems (e.g., Redis, Memcached) or in-memory.
 *
 * Cache provides a key-value store with optional TTL (time-to-live) support. All values are serialized
 * using KotlinX Serialization, allowing type-safe storage and retrieval of Kotlin objects.
 *
 * Implementations handle connection management, serialization, and platform-specific details.
 *
 * Example usage:
 * ```kotlin
 * val cache = Cache.Settings("redis://localhost:6379")("my-cache", context)
 * cache.set("user:123", user, timeToLive = 1.hours)
 * val cachedUser = cache.get<User>("user:123")
 * ```
 */
public interface Cache : Service {
    /**
     * Configuration for instantiating a Cache instance.
     *
     * The URL scheme determines the cache implementation:
     * - `ram` or `ram://` - Thread-safe in-memory cache (JVM uses ConcurrentHashMap, others use Mutex locks on writes)
     * - `ram-unsafe` - Non-thread-safe in-memory cache (faster, single-threaded use only)
     * - Other schemes registered by implementation modules (e.g., `redis://`, `memcached://`)
     *
     * @property url Connection string defining the cache type and connection parameters.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "ram"
    ) : Setting<Cache> {

        public companion object : UrlSettingParser<Cache>() {
            init {
                register("ram") { name, _, context -> MapCache(name, context) }
                register("ram-unsafe") { name, _, context -> MapCacheUnsafe(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): Cache {
            return parse(name, url, context)
        }
    }


    /**
     * Retrieves a value from the cache.
     *
     * @param key The cache key.
     * @param serializer Serializer for deserializing the cached value.
     * @return The cached value, or null if the key doesn't exist or has expired.
     */
    public suspend fun <T> get(key: String, serializer: KSerializer<T>): T?

    /**
     * Stores a value in the cache, overwriting any existing value.
     *
     * @param key The cache key.
     * @param value The value to cache.
     * @param serializer Serializer for serializing the value.
     * @param timeToLive Optional TTL. After this duration, the key is automatically removed. Null means no expiration.
     */
    public suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)

    /**
     * Stores a value only if the key does not already exist (atomic operation where supported).
     *
     * This is useful for implementing distributed locks or ensuring only one instance writes initial data.
     *
     * @param key The cache key.
     * @param value The value to cache.
     * @param serializer Serializer for serializing the value.
     * @param timeToLive Optional TTL. After this duration, the key is automatically removed. Null means no expiration.
     * @return true if the value was set, false if the key already existed.
     */
    public suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration? = null
    ): Boolean

    /**
     * Atomically modifies a cached value using optimistic locking (compare-and-swap pattern).
     *
     * Reads the current value, applies the modification function, and writes back only if the value
     * hasn't changed in the meantime. Retries up to [maxTries] times on conflicts.
     *
     * If the key doesn't exist, [modification] receives null. If it returns non-null, that value is inserted.
     * If [modification] returns null, the key is removed from the cache.
     *
     * **Important:** Implementations must provide proper atomic operations (like Redis WATCH/MULTI or
     * compare-and-set) to ensure correctness. The modification should be truly atomic to prevent lost updates
     * in concurrent scenarios.
     *
     * ## Implementation Guidelines
     *
     * Implementations should:
     * - Use native CAS operations where available (Redis WATCH, Memcached CAS, etc.)
     * - For in-memory implementations, use proper synchronization
     * - Return false only after exhausting all retries
     * - Ensure the modification function is called with the most recent value
     *
     * ## Usage Example
     *
     * ```kotlin
     * // Increment a counter safely
     * cache.modify<Int>("counter", maxTries = 5) { current ->
     *     (current ?: 0) + 1
     * }
     *
     * // Update a complex object
     * cache.modify<User>("user:123", maxTries = 3) { user ->
     *     user?.copy(lastSeen = Clock.System.now())
     * }
     * ```
     *
     * @param key The cache key.
     * @param serializer Serializer for the cached value.
     * @param maxTries Maximum retry attempts on conflicts. Default is 1 (no retries).
     * @param timeToLive Optional TTL for the updated value. Null means no expiration.
     * @param modification Function transforming the current value. Null input means key doesn't exist.
     * @return true if modification succeeded within [maxTries] attempts, false otherwise.
     */
    public suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if(compareAndSet(key, serializer, current, new, timeToLive)) return true
        }
        return false
    }

    public suspend fun <T> compareAndSet(
        key: String,
        serializer: KSerializer<T>,
        expected: T?,
        new: T?,
        timeToLive: Duration? = null
    ): Boolean {
        if (expected == new) return true
        if (expected != get(key, serializer)) return false
        if (new != null) {
            set(key, new, serializer, timeToLive)
        } else {
            remove(key)
        }
        return true
    }


    /**
     * Atomically increments a numeric value in the cache.
     *
     * If the key doesn't exist, it's created with the given value.
     * If the key exists with a non-numeric value, behavior is implementation-specific
     * (MapCache treats it as 0 and starts from [value]).
     *
     * @param key The cache key.
     * @param value The amount to add (can be negative for decrement).
     * @param timeToLive Optional TTL for the updated value. Null means no expiration.
     */
    public suspend fun add(key: String, value: Int, timeToLive: Duration? = null)

    /**
     * Removes a key from the cache.
     *
     * This operation is idempotent - removing a non-existent key succeeds silently.
     *
     * @param key The cache key to remove.
     */
    public suspend fun remove(key: String)

    /**
     * Verifies cache connectivity and basic operations.
     *
     * Performs a write-then-read test to ensure the cache is responsive and functional.
     * The test uses a temporary key with a 10-second validity check to detect stale responses.
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            set("health-check-test-key", Clock.System.now())
            // We check if the write occurred recently to ensure we're not just seeing stale information
            if (get<Instant>("health-check-test-key").let { it != null && it > Clock.System.now().minus(10.seconds) }) {
                HealthStatus(HealthStatus.Level.OK)
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

/*
 * TODO: API Recommendations for Cache interface:
 *
 * 1. Consider adding a `getOrSet` method for common cache-aside pattern:
 *    suspend fun <T> getOrSet(key: String, serializer: KSerializer<T>, timeToLive: Duration? = null, producer: suspend () -> T): T
 *
 * 2. Consider adding batch operations for efficiency:
 *    - suspend fun <T> getMany(keys: List<String>, serializer: KSerializer<T>): Map<String, T>
 *    - suspend fun <T> setMany(entries: Map<String, T>, serializer: KSerializer<T>, timeToLive: Duration? = null)
 *    - suspend fun removeMany(keys: List<String>)
 *
 * 3. The `modify` default implementation has a race condition (reads value twice without locking).
 *    Document that implementations should override with proper CAS operations, or consider removing
 *    the default implementation to force proper implementation.
 *
 * 4. Consider adding TTL refresh/update operation:
 *    suspend fun touch(key: String, timeToLive: Duration): Boolean
 *
 * 5. Consider adding pattern-based operations (where supported by backing store):
 *    - suspend fun keys(pattern: String): List<String>
 *    - suspend fun removePattern(pattern: String): Int
 *
 * 6. The `add` method only supports Int. Consider:
 *    - Supporting Long for larger counters
 *    - Renaming to `increment` for clarity
 *    - Adding a `decrement` convenience method
 */


