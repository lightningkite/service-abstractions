package com.lightningkite.services.cache

import kotlinx.serialization.serializer
import kotlin.time.Duration


/**
 * A Helper function for the underlying get call.
 * This can make get calls much cleaner and less wordy when the types can be inferred.
 */
public suspend inline fun <reified T : Any> Cache.get(key: String): T? {
    return get(key, context.internalSerializersModule.serializer<T>())
}

/**
 * A Helper function for the underlying set call.
 * This can make set calls much cleaner and less wordy when the types can be inferred.
 */
public suspend inline fun <reified T : Any> Cache.set(key: String, value: T, timeToLive: Duration? = null) {
    return set(key, value, context.internalSerializersModule.serializer<T>(), timeToLive)
}

/**
 * A Helper function for the underlying set setIfNotExists call.
 * This can make setIfNotExists calls much cleaner and less wordy when the types can be inferred.
 */
public suspend inline fun <reified T : Any> Cache.setIfNotExists(
    key: String,
    value: T,
    timeToLive: Duration? = null
): Boolean {
    return setIfNotExists(key, value, context.internalSerializersModule.serializer<T>(), timeToLive)
}


/**
 * A Helper function for the underlying set modify call.
 * This can make modify calls much cleaner and less wordy when the types can be inferred.
 */
public suspend inline fun <reified T : Any> Cache.modify(
    key: String,
    maxTries: Int = 1,
    timeToLive: Duration? = null,
    noinline modification: (T?) -> T?
): Boolean =
    modify(key, context.internalSerializersModule.serializer<T>(), maxTries, timeToLive, modification)


public inline operator fun <reified T> (() -> Cache).get(key: String): CacheHandle<T> =
    CacheHandle<T>(this, key) { this().context.internalSerializersModule.serializer<T>() }


internal expect fun platformSpecificCacheSettings()