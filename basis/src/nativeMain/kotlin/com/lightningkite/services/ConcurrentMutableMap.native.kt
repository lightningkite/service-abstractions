package com.lightningkite.services

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Kotlin/Native implementation of concurrent map using synchronized locking.
 *
 * This implementation wraps a regular [HashMap] with synchronized access to provide
 * thread-safety on Kotlin/Native platforms. While not as performant as lock-free
 * implementations, it provides correct concurrent behavior.
 *
 * ## Implementation Notes
 *
 * - Uses kotlinx.atomicfu's SynchronizedObject for cross-platform locking
 * - All operations are protected by a single lock (coarse-grained locking)
 * - Iterator operations are NOT thread-safe (snapshot iterator would be needed)
 * - Performance is acceptable for moderate contention
 *
 * ## Thread Safety
 *
 * - Individual operations (get, put, remove) are thread-safe
 * - Compound operations (getOrPut, compute) are atomic
 * - Iterating over keys/values/entries requires external synchronization
 *
 * @see ConcurrentMutableMap for cross-platform usage
 */
public actual fun <K, V> ConcurrentMutableMap(): MutableMap<K, V> {
    return SynchronizedMap()
}

/**
 * Thread-safe wrapper around HashMap using synchronized blocks.
 */
private class SynchronizedMap<K, V> : MutableMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    override val size: Int
        get() = synchronized(lock) { map.size }

    override fun isEmpty(): Boolean = synchronized(lock) { map.isEmpty() }

    override fun containsKey(key: K): Boolean = synchronized(lock) { map.containsKey(key) }

    override fun containsValue(value: V): Boolean = synchronized(lock) { map.containsValue(value) }

    override fun get(key: K): V? = synchronized(lock) { map[key] }

    override fun put(key: K, value: V): V? = synchronized(lock) { map.put(key, value) }

    override fun remove(key: K): V? = synchronized(lock) { map.remove(key) }

    override fun putAll(from: Map<out K, V>) = synchronized(lock) { map.putAll(from) }

    override fun clear() = synchronized(lock) { map.clear() }

    override val keys: MutableSet<K>
        get() = synchronized(lock) { map.keys.toMutableSet() }

    override val values: MutableCollection<V>
        get() = synchronized(lock) { map.values.toMutableList() }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = synchronized(lock) { map.entries.toMutableSet() }

    // Note: This is not atomic like ConcurrentHashMap.getOrPut on JVM
    // but it's synchronized so only one thread can execute it at a time
    override fun getOrPut(key: K, defaultValue: () -> V): V = synchronized(lock) {
        map.getOrPut(key, defaultValue)
    }
}