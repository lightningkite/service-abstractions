package com.lightningkite.services

/**
 * Creates a thread-safe mutable map suitable for concurrent access.
 *
 * This factory function provides platform-specific implementations of concurrent maps:
 * - **JVM/Android**: Returns [java.util.concurrent.ConcurrentHashMap]
 * - **JS**: Returns regular [mutableMapOf] (JS is single-threaded, no concurrency needed)
 * - **Native**: Returns [HashMap] (TODO: Not thread-safe, needs proper implementation)
 *
 * ## Usage
 *
 * Use when you need a map that will be accessed from multiple threads:
 * ```kotlin
 * class ServiceRegistry {
 *     private val services = ConcurrentMutableMap<String, Service>()
 *
 *     fun register(name: String, service: Service) {
 *         services[name] = service // Thread-safe on JVM
 *     }
 *
 *     fun get(name: String): Service? = services[name]
 * }
 * ```
 *
 * ## Platform Considerations
 *
 * - **JVM/Android**: Fully thread-safe, lock-free for most operations
 * - **JS**: No threading, so regular map is sufficient
 * - **Native**: Currently returns non-thread-safe HashMap (see Native implementation TODO)
 *
 * @param K The type of keys in the map
 * @param V The type of values in the map
 * @return A mutable map with platform-appropriate thread-safety guarantees
 */
public expect fun <K, V> ConcurrentMutableMap(): MutableMap<K, V>