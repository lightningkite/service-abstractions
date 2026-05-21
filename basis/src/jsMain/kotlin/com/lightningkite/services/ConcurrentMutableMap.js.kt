package com.lightningkite.services

/**
 * JS implementation backed by a regular [HashMap].
 *
 * JavaScript executes in a single-threaded event loop, so read-then-write within a single synchronous
 * function body is observationally atomic with respect to any other JS code. No explicit locking is
 * required, and [compute] / [computeIfAbsent] are simply a read followed by a write.
 *
 * @see ConcurrentMutableMap
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class ConcurrentMutableMap<K: Any, V: Any> actual constructor() : MutableMap<K, V> {
    private val map: HashMap<K, V> = HashMap()

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

    public actual fun compute(key: K, remapping: (K, V?) -> V?): V? {
        val existing = map[key]
        val new = remapping(key, existing)
        if (new == null) {
            map.remove(key)
        } else {
            map[key] = new
        }
        return new
    }

    public actual fun computeIfAbsent(key: K, mappingFn: (K) -> V): V {
        val existing = map[key]
        if (existing != null) return existing
        val new = mappingFn(key)
        map[key] = new
        return new
    }
}
