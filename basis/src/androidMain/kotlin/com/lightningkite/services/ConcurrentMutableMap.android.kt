package com.lightningkite.services

import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of concurrent map using [ConcurrentHashMap].
 *
 * Uses the same JVM implementation since Android runs on a JVM-compatible runtime.
 * [ConcurrentHashMap] is available on all Android API levels.
 *
 * @see ConcurrentMutableMap for cross-platform usage
 */
public actual fun <K, V> ConcurrentMutableMap(): MutableMap<K, V> = ConcurrentHashMap()
