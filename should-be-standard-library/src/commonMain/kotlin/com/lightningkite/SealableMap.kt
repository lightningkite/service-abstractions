package com.lightningkite

/**
 * A [MutableMap] that can be sealed to prevent any further modifications.
 *
 * Once [seal] is called, all mutation operations throw [IllegalStateException].
 * This is useful for creating immutable maps after an initial setup phase,
 * or for debugging to catch unintended modifications.
 *
 * ## Usage
 *
 * ```kotlin
 * val map = SealableMap<String, Int>()
 * map["a"] = 1
 * map["b"] = 2
 * map.seal()
 *
 * map["c"] = 3  // Throws IllegalStateException
 * ```
 *
 * Use [buildSealedMap] for a more convenient builder pattern:
 * ```kotlin
 * val map = buildSealedMap {
 *     put("a", 1)
 *     put("b", 2)
 * }  // Automatically sealed
 * ```
 *
 * @param K Key type
 * @param V Value type
 * @param wraps Underlying mutable map implementation (default: LinkedHashMap)
 */
public class SealableMap<K, V>(private val wraps: MutableMap<K, V> = LinkedHashMap()): MutableMap<K, V> by wraps {
    private var sealed: Exception? = null

    private inline fun <T> checkSealed(crossinline action: MutableMap<K, V>.() -> T): T {
        sealed?.let {
            throw IllegalStateException("Data has been sealed and cannot be modified. Sealed here: ${it.stackTraceToString()}")
        }
        return wraps.action()
    }

    override fun put(key: K, value: V): V? = checkSealed { put(key, value) }

    override fun remove(key: K): V? = checkSealed { remove(key) }

    override fun putAll(from: Map<out K, V>) = checkSealed { putAll(from) }

    override fun clear() = checkSealed { clear() }

    /**
     * Seals this map, preventing any future modifications.
     *
     * After calling this method, all mutation operations will throw [IllegalStateException].
     * This method is idempotent - calling it multiple times has no additional effect.
     *
     * The current stack trace is captured for debugging purposes and will be included
     * in the exception message if modification is attempted.
     */
    public fun seal() {
        if (sealed == null) sealed = Exception()
    }

    override fun toString(): String = wraps.toString()
}

/**
 * Creates a sealed map using a builder pattern.
 *
 * The provided [setup] block can mutate the map, but once the block completes,
 * the map is automatically sealed and returned as a read-only [Map].
 *
 * @param K Key type
 * @param V Value type
 * @param setup Builder block for populating the map
 * @return Sealed map that cannot be modified
 */
public fun <K, V> buildSealedMap(setup: MutableMap<K, V>.() -> Unit): Map<K, V> =
    SealableMap<K, V>().apply { setup(); seal() }

/**
 * Converts this [Map] to a sealed map.
 *
 * Creates a [SealableMap] from the entries of this map, then immediately seals it.
 * The returned map cannot be modified.
 *
 * @param K Key type
 * @param V Value type
 * @return Sealed map containing all entries from this map
 */
public fun <K, V> Map<K, V>.toSealedMap(): Map<K, V> = SealableMap(toMutableMap()).apply { seal() }