package com.lightningkite.services.database.internal

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi


/**
 * A [ConcurrentMap] for platforms without a native concurrent map. Reads are lock-free; every write copies the
 * whole map, so it suits caches that are written rarely and read often.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CopyOnWriteMap<K : Any, V : Any>(sizeHint: Int) : AbstractMutableMap<K, V>(), ConcurrentMap<K, V> {
    // Every map stored here is fully built before it's published and never mutated afterwards.
    private val snapshot = AtomicReference<Map<K, V>>(HashMap(sizeHint))

    private inline fun <R> mutate(block: HashMap<K, V>.() -> R): R {
        while (true) {
            val current = snapshot.load()
            val next = HashMap(current)
            val result = next.block()
            if (snapshot.compareAndSet(current, next)) return result
        }
    }

    override val size: Int get() = snapshot.load().size
    override fun isEmpty(): Boolean = snapshot.load().isEmpty()
    override fun containsKey(key: K): Boolean = snapshot.load().containsKey(key)
    override fun containsValue(value: V): Boolean = snapshot.load().containsValue(value)
    override fun get(key: K): V? = snapshot.load()[key]

    override fun put(key: K, value: V): V? = mutate { put(key, value) }
    override fun remove(key: K): V? = mutate { remove(key) }
    override fun putAll(from: Map<out K, V>) = mutate { putAll(from) }
    override fun clear() = mutate { clear() }

    override fun putIfAbsent(key: K, value: V): V? {
        while (true) {
            val current = snapshot.load()
            current[key]?.let { return it }
            val next = HashMap(current)
            next[key] = value
            if (snapshot.compareAndSet(current, next)) return null
        }
    }

    // A write-through view over the snapshot current when iteration starts.
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = this@CopyOnWriteMap.size

            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean =
                put(element.key, element.value) != element.value

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> =
                object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    private val source = snapshot.load().entries.iterator()
                    private var last: K? = null

                    override fun hasNext(): Boolean = source.hasNext()

                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val entry = source.next()
                        last = entry.key
                        return Entry(entry.key, entry.value)
                    }

                    override fun remove() {
                        val key = checkNotNull(last) { "next() has not been called, or remove() was already called" }
                        this@CopyOnWriteMap.remove(key)
                        last = null
                    }
                }
        }

    private inner class Entry(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            val old = value
            put(key, newValue)
            value = newValue
            return old
        }

        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode(): Int = key.hashCode() xor value.hashCode()
        override fun toString(): String = "$key=$value"
    }
}

internal actual fun <K : Any, V : Any> ConcurrentMap(sizeHint: Int): ConcurrentMap<K, V> =
    CopyOnWriteMap(sizeHint)