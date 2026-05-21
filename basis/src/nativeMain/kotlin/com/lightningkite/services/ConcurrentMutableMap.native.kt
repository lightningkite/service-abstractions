package com.lightningkite.services

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Kotlin/Native implementation backed by a [HashMap] guarded by `kotlinx.atomicfu` synchronized blocks.
 *
 * All operations acquire a single coarse-grained lock — including the compound [compute] and
 * [computeIfAbsent], which hold the lock for the entire read-compute-write sequence so no other thread
 * can observe an intermediate state.
 *
 * [keys], [values], and [entries] return snapshot collections (constructed under the lock) to keep
 * iteration safe in the face of concurrent mutation.
 *
 * @see ConcurrentMutableMap
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class ConcurrentMutableMap<K: Any, V: Any> actual constructor() : MutableMap<K, V> {
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
    override fun putAll(from: Map<out K, V>): Unit = synchronized(lock) { map.putAll(from) }
    override fun clear(): Unit = synchronized(lock) { map.clear() }

    override val keys: MutableSet<K>
        get() = synchronized(lock) { map.keys.toMutableSet() }

    override val values: MutableCollection<V>
        get() = synchronized(lock) { map.values.toMutableList() }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = synchronized(lock) { map.entries.toMutableSet() }

    public actual fun compute(key: K, remapping: (K, V?) -> V?): V? = synchronized(lock) {
        val existing = map[key]
        val new = remapping(key, existing)
        if (new == null) {
            map.remove(key)
        } else {
            map[key] = new
        }
        new
    }

    public actual fun computeIfAbsent(key: K, mappingFn: (K) -> V): V = synchronized(lock) {
        val existing = map[key]
        if (existing != null) return@synchronized existing
        val new = mappingFn(key)
        map[key] = new
        new
    }
}
