package com.lightningkite

/**
 * A [MutableMap] that can be sealed to prevent any further modifications to the underlying data.
 * */
public class SealableMap<K, V>(private val wraps: MutableMap<K, V>): MutableMap<K, V> by wraps {
    private var sealed: Exception? = null

    private inline fun <T> checkSealed(crossinline action: MutableMap<K, V>.() -> T): T {
        sealed?.let {
            throw IllegalStateException("Data has been sealed and cannot be modified: Sealed here: ${it.printStackTrace()}")
        }
        return wraps.action()
    }

    override fun put(key: K, value: V): V? = checkSealed { put(key, value) }

    override fun remove(key: K): V? = checkSealed { remove(key) }

    override fun putAll(from: Map<out K, V>) = checkSealed { putAll(from) }

    override fun clear() = checkSealed { clear() }

    public fun seal() {
        sealed = Exception()
    }
}

public fun <K, V> Map<K, V>.toSealedMap(): Map<K, V> = SealableMap(toMutableMap()).apply { seal() }