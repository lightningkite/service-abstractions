package com.lightningkite

/**
 * A [MutableMap] that can be sealed to prevent any further modifications to the underlying data.
 * */
public class SealableMap<K, V>(private val wraps: MutableMap<K, V> = LinkedHashMap()): MutableMap<K, V> by wraps {
    private var sealed: Exception? = null

    private inline fun <T> checkSealed(crossinline action: MutableMap<K, V>.() -> T): T {
        sealed?.let {
            throw IllegalStateException("Data has been sealed and cannot be modified. Sealed here: ${it.printStackTrace()}")
        }
        return wraps.action()
    }

    override fun put(key: K, value: V): V? = checkSealed { put(key, value) }

    override fun remove(key: K): V? = checkSealed { remove(key) }

    override fun putAll(from: Map<out K, V>) = checkSealed { putAll(from) }

    override fun clear() = checkSealed { clear() }

    public fun seal() {
        if (sealed == null) sealed = Exception()
    }

    override fun toString(): String = wraps.toString()
}

public fun <K, V> buildSealedMap(setup: MutableMap<K, V>.() -> Unit): Map<K, V> =
    SealableMap<K, V>().apply { setup(); seal() }

public fun <K, V> Map<K, V>.toSealedMap(): Map<K, V> = SealableMap(toMutableMap()).apply { seal() }