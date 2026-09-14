package com.lightningkite.services

/**
 * A factory interface for creating service instances from configuration.
 *
 * `Setting<T>` represents a serializable service configuration that can instantiate
 * a service of type `T`. This is the core abstraction that enables:
 * - Declaring service dependencies in configuration files
 * - Switching service implementations via configuration (e.g., MongoDB → PostgreSQL)
 * - Type-safe service instantiation at runtime
 *
 * ## Usage Pattern
 *
 * Services define a companion object that implements `Setting`:
 * ```kotlin
 * interface Cache {
 *     suspend fun get(key: String): String?
 *     suspend fun set(key: String, value: String)
 *
 *     companion object : Setting<Cache> {
 *         override fun invoke(name: String, context: SettingContext): Cache {
 *             // Parse URL and return appropriate implementation
 *             return when {
 *                 url.startsWith("redis://") -> RedisCache(name, url, context)
 *                 url.startsWith("ram://") -> InMemoryCache(name, context)
 *                 else -> error("Unknown cache type")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * ## Serialization
 *
 * Settings are typically serializable configuration objects:
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val database: Database.Settings = Database.Settings("mongodb://localhost"),
 *     val cache: Cache.Settings = Cache.Settings("redis://localhost")
 * )
 *
 * // Later, instantiate services:
 * val db = settings.database("main-db", context)
 * val cache = settings.cache("app-cache", context)
 * ```
 *
 * @param T The type of service this setting can instantiate
 * @see SettingContext for the context passed during instantiation
 * @see UrlSettingParser for URL-based configuration parsing
 */
public interface Setting<T> {
    /**
     * Creates an instance of the service.
     *
     * @param name Unique identifier for this service instance (used for logging, metrics)
     * @param context Runtime context containing serializers, telemetry, and shared resources
     * @return A configured service instance
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public operator fun invoke(name: String, context: SettingContext): T
}
