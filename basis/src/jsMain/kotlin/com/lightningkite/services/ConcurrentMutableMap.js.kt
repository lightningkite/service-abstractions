package com.lightningkite.services

/**
 * JavaScript implementation of concurrent map.
 *
 * JavaScript is single-threaded in its execution model, so no special thread-safety
 * mechanisms are needed. A regular [mutableMapOf] is sufficient.
 *
 * Note: While JS has Workers and async operations, the event loop ensures that
 * no two pieces of JavaScript code run simultaneously, making explicit locking unnecessary.
 *
 * @see ConcurrentMutableMap for cross-platform usage
 */
public actual fun <K, V> ConcurrentMutableMap(): MutableMap<K, V> = mutableMapOf()