package com.lightningkite.services.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A cache key that knows how its value is stored, so every read and write of it agrees on the encoding.
 *
 * Create one with [CacheKey] for any serializable value, or use [OfLong], [OfInt], or [OfInstant] for values
 * that can also be atomically added to with [Cache.add]. Each operation mirrors the [Cache] member of the
 * same name.
 */
public interface CacheKey<T> {
    public val key: String

    public suspend fun get(cache: Cache): T?
    public suspend fun set(cache: Cache, value: T, timeToLive: Duration? = null)
    public suspend fun setIfNotExists(cache: Cache, value: T, timeToLive: Duration? = null): Boolean
    public suspend fun compareAndSet(cache: Cache, expected: T?, new: T?, timeToLive: Duration? = null): Boolean
    public suspend fun modify(cache: Cache, maxTries: Int = 1, timeToLive: Duration? = null, modification: (T?) -> T?): Boolean
    public suspend fun getAndRemove(cache: Cache): T?
    public suspend fun remove(cache: Cache): Unit = cache.remove(key)

    /** A [CacheKey] whose value can be atomically incremented by an amount of type [ADD]. */
    public interface Addable<T, ADD> : CacheKey<T> {
        /** Adds [value] to the stored value, or stores [value] if the key is missing, and returns the result. */
        public suspend fun add(cache: Cache, value: ADD, timeToLive: Duration? = null): T
    }

    /** A [CacheKey] for a `Long` counter. */
    public class OfLong(key: String) : Encoded<Long, Long>(key, Long.serializer()), Addable<Long, Long> {
        override fun encode(value: Long): Long = value
        override fun decode(stored: Long): Long = stored
        override suspend fun add(cache: Cache, value: Long, timeToLive: Duration?): Long =
            cache.add(key, value, timeToLive)
    }

    /** A [CacheKey] for an `Int` counter. */
    public class OfInt(key: String) : Encoded<Int, Int>(key, Int.serializer()), Addable<Int, Int> {
        override fun encode(value: Int): Int = value
        override fun decode(stored: Int): Int = stored
        override suspend fun add(cache: Cache, value: Int, timeToLive: Duration?): Int =
            cache.add(key, value, timeToLive)
    }

    /** A [CacheKey] that stores an [Instant] as epoch milliseconds, so a [Duration] can be added to it. */
    public class OfInstant(key: String) : Encoded<Instant, Long>(key, Long.serializer()), Addable<Instant, Duration> {
        override fun encode(value: Instant): Long = value.toEpochMilliseconds()
        override fun decode(stored: Long): Instant = Instant.fromEpochMilliseconds(stored)
        override suspend fun add(cache: Cache, value: Duration, timeToLive: Duration?): Instant =
            decode(cache.add(key, value.inWholeMilliseconds, timeToLive))
    }

    /**
     * A [CacheKey] that stores each value of type [T] as a value of type [S], written with [stored].
     *
     * Extend it to store a type in a form a cache handles better, as [OfInstant] does.
     */
    public abstract class Encoded<T, S>(
        override val key: String,
        private val stored: KSerializer<S>,
    ) : CacheKey<T> {
        protected abstract fun encode(value: T): S
        protected abstract fun decode(stored: S): T

        override suspend fun get(cache: Cache): T? = cache.get(key, stored)?.let(::decode)

        override suspend fun set(cache: Cache, value: T, timeToLive: Duration?): Unit =
            cache.set(key, encode(value), stored, timeToLive)

        override suspend fun setIfNotExists(cache: Cache, value: T, timeToLive: Duration?): Boolean =
            cache.setIfNotExists(key, encode(value), stored, timeToLive)

        override suspend fun compareAndSet(cache: Cache, expected: T?, new: T?, timeToLive: Duration?): Boolean =
            cache.compareAndSet(key, stored, expected?.let(::encode), new?.let(::encode), timeToLive)

        override suspend fun modify(cache: Cache, maxTries: Int, timeToLive: Duration?, modification: (T?) -> T?): Boolean =
            cache.modify(key, stored, maxTries, timeToLive) { current -> modification(current?.let(::decode))?.let(::encode) }

        override suspend fun getAndRemove(cache: Cache): T? = cache.getAndRemove(key, stored)?.let(::decode)

        override fun toString(): String = key
    }
}

/** Returns a [CacheKey] that stores its value with [serializer]. */
public fun <T> CacheKey(key: String, serializer: KSerializer<T>): CacheKey<T> =
    object : CacheKey.Encoded<T, T>(key, serializer) {
        override fun encode(value: T): T = value
        override fun decode(stored: T): T = stored
    }

/** Returns a [CacheKey] that stores its value with the serializer for [T]. */
public inline fun <reified T> CacheKey(key: String): CacheKey<T> = CacheKey(key, serializer())

public suspend fun <T> Cache.get(key: CacheKey<T>): T? = key.get(this)

public suspend fun <T> Cache.set(key: CacheKey<T>, value: T, timeToLive: Duration? = null): Unit = key.set(this, value, timeToLive)

public suspend fun <T> Cache.setIfNotExists(key: CacheKey<T>, value: T, timeToLive: Duration? = null): Boolean =
    key.setIfNotExists(this, value, timeToLive)

public suspend fun <T> Cache.compareAndSet(key: CacheKey<T>, expected: T?, new: T?, timeToLive: Duration? = null): Boolean =
    key.compareAndSet(this, expected, new, timeToLive)

public suspend fun <T> Cache.modify(key: CacheKey<T>, maxTries: Int = 1, timeToLive: Duration? = null, modification: (T?) -> T?): Boolean =
    key.modify(this, maxTries, timeToLive, modification)

public suspend fun <T> Cache.getAndRemove(key: CacheKey<T>): T? = key.getAndRemove(this)

public suspend fun Cache.remove(key: CacheKey<*>): Unit = key.remove(this)

public suspend fun <T, ADD> Cache.add(key: CacheKey.Addable<T, ADD>, value: ADD, timeToLive: Duration? = null): T =
    key.add(this, value, timeToLive)
