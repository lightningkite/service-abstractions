package com.lightningkite.services.database.internal

internal interface ConcurrentMap<K : Any, V : Any> : MutableMap<K, V> {
    /** Atomically stores [value] if [key] is absent. Returns the existing value, or null if [value] was stored. */
    fun putIfAbsent(key: K, value: V): V?
}

internal expect fun <K : Any, V : Any> ConcurrentMap(sizeHint: Int = 16): ConcurrentMap<K, V>

/**
 * Returns the value for [key], storing the result of [value] if there is none.
 * [value] runs without holding any lock and may run more than once under contention; only one result is kept
 * and returned to every caller.
 */
// Deliberately not named computeIfAbsent or getOrPut: on JVM those resolve to Map.computeIfAbsent (a member, which
// locks while computing) or the stdlib's non-atomic getOrPut when this helper isn't imported.
internal inline fun <K : Any, V : Any> ConcurrentMap<K, V>.getOrPutConcurrent(key: K, value: () -> V): V =
    get(key) ?: value().also { created -> putIfAbsent(key, created) }
