package com.lightningkite.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation backed by [ConcurrentHashMap], identical in behavior to the JVM implementation.
 * Available on all supported Android API levels.
 *
 * @see ConcurrentMutableMap
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class ConcurrentMutableMap<K: Any, V: Any> actual constructor() : MutableMap<K, V> {
    private val map: ConcurrentHashMap<K, V> = ConcurrentHashMap()

    actual override val size: Int get() = map.size
    actual override fun isEmpty(): Boolean = map.isEmpty()
    actual override fun containsKey(key: K): Boolean = map.containsKey(key)
    actual override fun containsValue(value: V): Boolean = map.containsValue(value)
    actual override fun get(key: K): V? = map[key]
    actual override fun put(key: K, value: V): V? = map.put(key, value)
    actual override fun remove(key: K): V? = map.remove(key)
    actual override fun putAll(from: Map<out K, V>): Unit = map.putAll(from)
    actual override fun clear(): Unit = map.clear()
    actual override val keys: MutableSet<K> get() = map.keys
    actual override val values: MutableCollection<V> get() = map.values
    actual override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = map.entries

    public actual fun compute(key: K, remapping: (K, V?) -> V?): V? =
        map.compute(key) { k, v -> remapping(k, v) }

    public actual fun computeIfAbsent(key: K, mappingFn: (K) -> V): V =
        map.computeIfAbsent(key) { k -> mappingFn(k) }
}
