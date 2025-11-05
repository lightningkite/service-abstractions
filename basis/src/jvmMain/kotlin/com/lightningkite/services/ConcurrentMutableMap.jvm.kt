package com.lightningkite.services

import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation of concurrent map using [ConcurrentHashMap].
 *
 * [ConcurrentHashMap] provides:
 * - Thread-safe operations without requiring external synchronization
 * - Lock-free reads for better performance
 * - Segmented locking for writes to reduce contention
 * - Null-safe (doesn't allow null keys or values)
 *
 * @see ConcurrentMutableMap for cross-platform usage
 */
public actual fun <K, V> ConcurrentMutableMap(): MutableMap<K, V> = ConcurrentHashMap()
