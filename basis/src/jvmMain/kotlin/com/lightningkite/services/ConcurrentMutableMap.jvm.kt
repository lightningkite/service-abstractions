package com.lightningkite.services

import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation backed by [ConcurrentHashMap].
 *
 * Reads are lock-free; writes use the standard `ConcurrentHashMap` striped locking. The [compute] and
 * [computeIfAbsent] operations delegate directly to their `ConcurrentHashMap` counterparts, which guarantee
 * atomicity of the read-modify-write on a per-key basis.
 *
 * ## Null values
 *
 * [ConcurrentHashMap] does not allow null keys or null values. Storing a `null` value is not supported;
 * use [compute] returning `null` to remove an entry instead.
 *
 * @see ConcurrentMutableMap
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class ConcurrentMutableMap<K, V> actual constructor() : MutableMap<K, V> {
    private val map: ConcurrentHashMap<K, V> = ConcurrentHashMap()

    override val size: Int get() = map.size
    override fun isEmpty(): Boolean = map.isEmpty()
    override fun containsKey(key: K): Boolean = map.containsKey(key)
    override fun containsValue(value: V): Boolean = map.containsValue(value)
    override fun get(key: K): V? = map[key]
    override fun put(key: K, value: V): V? = map.put(key, value)
    override fun remove(key: K): V? = map.remove(key)
    override fun putAll(from: Map<out K, V>): Unit = map.putAll(from)
    override fun clear(): Unit = map.clear()
    override val keys: MutableSet<K> get() = map.keys
    override val values: MutableCollection<V> get() = map.values
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries

    public actual fun compute(key: K, remapping: (K, V?) -> V?): V? =
        map.compute(key) { k, v -> remapping(k, v) }

    public actual fun computeIfAbsent(key: K, mappingFn: (K) -> V): V =
        map.computeIfAbsent(key) { k -> mappingFn(k) }
}
