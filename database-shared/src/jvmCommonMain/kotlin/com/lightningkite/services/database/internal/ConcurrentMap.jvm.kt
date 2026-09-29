package com.lightningkite.services.database.internal

import java.util.concurrent.ConcurrentHashMap

private class ConcurrentHashMapWrapper<K : Any, V : Any>(val map: ConcurrentHashMap<K, V>) :
    ConcurrentMap<K, V>, MutableMap<K, V> by map {
    // Must be explicit: class delegation doesn't forward JDK default methods, so without this the wrapper would
    // inherit Map.putIfAbsent's non-atomic get-then-put.
    override fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)
}

internal actual fun <K : Any, V : Any> ConcurrentMap(sizeHint: Int): ConcurrentMap<K, V> =
    ConcurrentHashMapWrapper(ConcurrentHashMap(sizeHint))
