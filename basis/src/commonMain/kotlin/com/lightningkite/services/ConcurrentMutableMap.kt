package com.lightningkite.services

/**
 * A thread-safe [MutableMap] suitable for concurrent access from multiple threads.
 *
 * Provides platform-specific implementations:
 * - **JVM/Android**: Backed by [java.util.concurrent.ConcurrentHashMap] (lock-free reads, fine-grained write locking).
 * - **JS**: Backed by a regular [mutableMapOf] (JS is single-threaded; no synchronization needed).
 * - **Native**: Backed by a [HashMap] guarded by `kotlinx.atomicfu.locks.SynchronizedObject` (coarse-grained locking).
 *
 * ## Atomic operations
 *
 * In addition to the regular [MutableMap] API, this class exposes [compute] and [computeIfAbsent] which are
 * atomic — the read-compute-write happens without any other thread observing or modifying the same key
 * in between. These mirror the semantics of [java.util.concurrent.ConcurrentHashMap.compute] /
 * [java.util.concurrent.ConcurrentHashMap.computeIfAbsent] and are the foundation for CAS-style cache
 * operations built on this map.
 *
 * ## Iteration is NOT atomic
 *
 * Iterating over [keys], [values], or [entries] returns a snapshot on Native but is not guaranteed to be
 * a consistent point-in-time view of the map across all platforms. Callers that need a stable snapshot
 * should defensively call `toMap()`. Do not assume an iterator reflects changes made to the map after
 * it was obtained.
 *
 * ## Usage
 *
 * ```kotlin
 * val cache = ConcurrentMutableMap<String, Entry>()
 *
 * // Plain map operations
 * cache["key"] = Entry(...)
 * val value = cache["key"]
 *
 * // Atomic read-modify-write
 * cache.compute("counter") { _, existing ->
 *     Entry((existing?.value ?: 0) + 1)
 * }
 * ```
 *
 * @param K The type of keys.
 * @param V The type of values.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public expect class ConcurrentMutableMap<K: Any, V: Any>() : MutableMap<K, V> {

    /**
     * Atomically read the current value for [key] (or `null` if absent), invoke [remapping] on it,
     * and store the result. If [remapping] returns `null`, the key is removed.
     *
     * No other thread can observe a partial state: the entire read-compute-write executes as a single
     * atomic step with respect to other operations on the same key.
     *
     * @param key The key to operate on.
     * @param remapping Function receiving the key and the current value (or `null` if absent). Returns
     *   the new value to store, or `null` to remove the entry.
     * @return The value that is now associated with [key], or `null` if the entry was removed or
     *   [remapping] returned `null` for an absent key.
     */
    public fun compute(key: K, remapping: (K, V?) -> V?): V?

    /**
     * Atomically compute and store a value for [key] only if no value is currently associated with it.
     *
     * Equivalent to [java.util.concurrent.ConcurrentHashMap.computeIfAbsent]. The [mappingFn] is invoked
     * at most once per call and only when the key has no associated value.
     *
     * @param key The key to operate on.
     * @param mappingFn Function producing the value to associate with [key] if absent.
     * @return The existing value, or the newly computed one if the key was absent.
     */
    public fun computeIfAbsent(key: K, mappingFn: (K) -> V): V
    override val keys: MutableSet<K>
    override val values: MutableCollection<V>
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
    override fun put(key: K, value: V): V?
    override fun remove(key: K): V?
    override fun putAll(from: Map<out K, V>)
    override fun clear()
    override val size: Int
    override fun isEmpty(): Boolean
    override fun containsKey(key: K): Boolean
    override fun containsValue(value: V): Boolean
    override fun get(key: K): V?
}
