package com.lightningkite.services

/**
 * Lazy-initialized resource pool for sharing expensive objects across services.
 *
 * `SharedResources` implements a keyed registry pattern where resources are:
 * - Created on-demand when first requested
 * - Cached for subsequent requests
 * - Shared across all services that use the same context
 *
 * This is essential for:
 * - **Connection pooling**: Share HTTP clients, database connection pools
 * - **Thread pools**: Share executor services for blocking I/O
 * - **SSL/TLS contexts**: Expensive to create, safe to share
 * - **Rate limiters**: Coordinate rate limiting across services
 * - **Circuit breakers**: Share circuit breaker state
 *
 * ## Usage Pattern
 *
 * Define a resource key (typically as a companion object):
 * ```kotlin
 * object MyHttpClient {
 *     object Key : SharedResources.Key<HttpClient> {
 *         override fun setup(context: SettingContext): HttpClient {
 *             return HttpClient {
 *                 expectSuccess = true
 *                 install(Timeout) { requestTimeoutMillis = 30_000 }
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * Access the resource from a service:
 * ```kotlin
 * class MyService(override val context: SettingContext) : Service {
 *     private val httpClient = context[MyHttpClient.Key]
 *     // Now use httpClient - it's shared across all services
 * }
 * ```
 *
 * ## Thread Safety
 *
 * The current implementation uses [MutableMap.getOrPut] which is **not thread-safe**.
 * In multi-threaded scenarios, this could result in [Key.setup] being called multiple times
 * for the same key.
 *
 * @property map Internal storage for cached resources (default: HashMap)
 */
public class SharedResources(private val map: MutableMap<Key<*>, Any?> = ConcurrentMutableMap()) {
    /**
     * Resource key that knows how to create its associated resource.
     *
     * Implement this interface to define a new shared resource type.
     * The [setup] method is called exactly once* per key to create the resource.
     *
     * *Note: Due to thread-safety issue, setup may be called multiple times in concurrent scenarios.
     *
     * @param T The type of resource this key creates
     */
    public interface Key<T> {
        /**
         * Creates and initializes the resource.
         *
         * Called when the resource is first requested. The returned value is cached
         * and returned for all subsequent requests.
         *
         * ## Guidelines
         *
         * - Should be idempotent if possible (due to potential concurrent calls)
         * - Should not throw exceptions if possible (no retry mechanism)
         * - May perform expensive initialization (only called once)
         * - Should respect the provided context (e.g., use context.clock, not Clock.System)
         *
         * @param context The setting context, providing access to configuration and other resources
         * @return The initialized resource instance
         */
        public fun setup(context: SettingContext): T
    }

    /**
     * Retrieves a resource, creating it if necessary.
     *
     * If the resource hasn't been created yet, calls [Key.setup] to initialize it,
     * caches the result, and returns it. Subsequent calls return the cached instance.
     *
     * @param key Resource key identifying which resource to get
     * @param context Setting context passed to setup if resource needs creation
     * @return The resource instance (either cached or newly created)
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> get(key: Key<T>, context: SettingContext): T = map.getOrPut(key) { key.setup(context) } as T
}
