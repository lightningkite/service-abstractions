package com.lightningkite.services.cache

import kotlinx.serialization.serializer
import kotlin.time.Duration


/**
 * Retrieves a value with type inference, eliminating the need to explicitly provide a serializer.
 *
 * ```kotlin
 * val user: User? = cache.get("user:123")  // Type inferred from context
 * ```
 *
 * @param T The type to deserialize. Must be registered in the context's SerializersModule.
 * @return The cached value, or null if the key doesn't exist or has expired.
 */
public suspend inline fun <reified T : Any> Cache.get(key: String): T? {
    return get(key, context.internalSerializersModule.serializer<T>())
}

/**
 * Stores a value with type inference, eliminating the need to explicitly provide a serializer.
 *
 * ```kotlin
 * cache.set("user:123", user, timeToLive = 1.hours)  // Serializer inferred from type
 * ```
 *
 * @param T The type to serialize. Must be registered in the context's SerializersModule.
 */
public suspend inline fun <reified T : Any> Cache.set(key: String, value: T, timeToLive: Duration? = null) {
    return set(key, value, context.internalSerializersModule.serializer<T>(), timeToLive)
}

/**
 * Conditionally stores a value with type inference, only if the key doesn't exist.
 *
 * ```kotlin
 * val wasSet = cache.setIfNotExists("user:123", user, timeToLive = 1.hours)
 * ```
 *
 * @param T The type to serialize. Must be registered in the context's SerializersModule.
 * @return true if the value was stored, false if the key already existed.
 */
public suspend inline fun <reified T : Any> Cache.setIfNotExists(
    key: String,
    value: T,
    timeToLive: Duration? = null
): Boolean {
    return setIfNotExists(key, value, context.internalSerializersModule.serializer<T>(), timeToLive)
}


/**
 * Atomically modifies a value with type inference using compare-and-swap.
 *
 * ```kotlin
 * cache.modify<Counter>("visits", maxTries = 5) { current ->
 *     current?.copy(count = current.count + 1) ?: Counter(1)
 * }
 * ```
 *
 * @param T The type to serialize/deserialize. Must be registered in the context's SerializersModule.
 * @return true if modification succeeded within maxTries, false otherwise.
 */
public suspend inline fun <reified T : Any> Cache.modify(
    key: String,
    maxTries: Int = 1,
    timeToLive: Duration? = null,
    noinline modification: (T?) -> T?
): Boolean =
    modify(key, context.internalSerializersModule.serializer<T>(), maxTries, timeToLive, modification)


/**
 * Creates a type-safe handle for a specific cache key using subscript syntax.
 *
 * ```kotlin
 * val cache: () -> Cache = { myCache }
 * val userHandle: CacheHandle<User> = cache["user:123"]
 * userHandle.set(user)
 * val cachedUser = userHandle.get()
 * ```
 *
 * @param T The value type for this cache key.
 * @param key The cache key to bind to this handle.
 * @return A CacheHandle that operates on the specified key with type T.
 */
public inline operator fun <reified T> (() -> Cache).get(key: String): CacheHandle<T> =
    CacheHandle<T>(this, key) { this().context.internalSerializersModule.serializer<T>() }
