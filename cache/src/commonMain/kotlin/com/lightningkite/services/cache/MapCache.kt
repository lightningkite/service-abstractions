package com.lightningkite.services.cache

import com.lightningkite.services.ConcurrentMutableMap
import com.lightningkite.services.SettingContext
import com.lightningkite.services.default
import kotlinx.serialization.KSerializer
import kotlin.time.*

/**
 * An in-memory, thread-safe cache implementation backed by [ConcurrentMutableMap].
 *
 * Stores values with optional TTL support. The backing [ConcurrentMutableMap] provides atomic
 * read-compute-write semantics on every Kotlin Multiplatform target — JVM/Android via
 * `ConcurrentHashMap.compute`, Native via synchronized locking, JS via a single-threaded event loop.
 * This is what makes the [setIfNotExists], [add], and [modify] operations correct under concurrent use.
 *
 * **Expiration:** Expired entries are checked on access but not proactively removed, so memory usage
 * can grow if expired keys aren't accessed. Consider periodic cleanup for long-running applications.
 *
 * @property name The service name.
 * @property context The setting context (provides clock, serializers, telemetry).
 */
public class MapCache(
    override val name: String,
    override val context: SettingContext,
) : Cache {
    private val entries: ConcurrentMutableMap<String, Entry> = ConcurrentMutableMap()

    /**
     * A cache entry with optional expiration.
     * @property value The cached value (untyped for storage efficiency).
     * @property expires The instant when this entry expires, or null for no expiration.
     */
    public class Entry(public val value: Any?, public val expires: Instant? = null)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return instrumentedGet(context, key) {
            entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }?.value as? T
        }
    }

    override suspend fun <T> set(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ) {
        assertValidTtl(timeToLive)
        instrumentedSet<T>(context, key, timeToLive) {
            entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
        }
    }

    /**
     * Removes all entries from the cache.
     *
     * **Warning:** Not part of the Cache interface, so it won't be available via polymorphic references.
     */
    public fun clear() {
        entries.clear()
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
        assertValidTtl(timeToLive)
        return instrumentedSetIfNotExists(context, key, timeToLive) {
            // compute is atomic per key on every platform; success flag captured via closure
            // because compute's own return value can't distinguish "inserted" vs "kept existing".
            var success = false
            val clock = Clock.default()
            entries.compute(key) { _, existing ->
                if (existing == null || (existing.expires != null && existing.expires <= clock.now())) {
                    success = true
                    Entry(value, timeToLive?.let { clock.now() + it })
                } else existing
            }
            success
        }
    }

    private suspend fun add(key: String, value: Long, default: Number, timeToLive: Duration?): Number {
        assertValidTtl(timeToLive)
        return instrumentedAdd(context, key, value, timeToLive) {
            val clock = Clock.default()
            val r = entries.compute(key) { _, existing ->
                val entry = existing?.takeIf { it.expires == null || it.expires > clock.now() }
                val new = when (val current = entry?.value) {
                    is Byte -> (current + value).toByte()
                    is Short -> (current + value).toShort()
                    is Int -> (current + value).toInt()
                    is Long -> (current + value)
                    is Float -> (current + value)
                    is Double -> (current + value)
                    else -> default   // explicitly use the type provided
                }
                Entry(new, timeToLive?.let { clock.now() + it })
            } ?: throw IllegalStateException("Could not modify entry correctly")
            r.value as Number
        }
    }

    override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        add(key, value, default = value, timeToLive).toLong()

    override suspend fun add(key: String, value: Int, timeToLive: Duration?): Int =
        add(key, value = value.toLong(), default = value, timeToLive).toInt()

    override suspend fun remove(key: String) {
        instrumentedRemove(context, key) {
            entries.remove(key)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?,
    ): Boolean {
        assertValidTtl(timeToLive)
        return instrumentedModify<T>(context, key, maxTries, timeToLive) {
            // CAS loop: read current value, compute new value, atomically swap only if unchanged.
            // Retries up to maxTries times on concurrent modification so the modification function
            // always operates on the most recent value.
            val clock = Clock.default()
            repeat(maxTries) {
                val existing = entries[key]?.takeIf { it.expires == null || it.expires > clock.now() }
                val current = existing?.value as? T
                val new = modification(current)
                val newEntry = new?.let { Entry(it, timeToLive?.let { clock.now() + it } ?: existing?.expires) }
                val swapped: Boolean
                if (newEntry != null) {
                    // Replace only if the stored entry is still the one we read.
                    var cas = false
                    entries.compute(key) { _, cur ->
                        if (cur === existing) { cas = true; newEntry } else cur
                    }
                    swapped = cas
                } else {
                    // new == null: delete only if value hasn't changed since we read it.
                    var cas = false
                    entries.compute(key) { _, cur ->
                        if (cur === existing) { cas = true; null } else cur
                    }
                    swapped = cas
                }
                if (swapped) return@instrumentedModify true
            }
            false
        }
    }
}
