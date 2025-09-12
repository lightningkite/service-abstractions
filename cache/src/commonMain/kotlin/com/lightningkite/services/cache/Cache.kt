package com.lightningkite.services.cache

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An abstracted model for caching data outside the running program using, for example, Redis or Memcached.
 * Every implementation will handle how to get and set values in the underlying cache system.
 */
public interface Cache : Service {
    /**
     * Settings that define what cache to use and how to connect to it.
     *
     * @param url Defines the type and connection to the cache. Built-in options are local.
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "ram"
    ) : Setting<Cache> {

        public companion object : UrlSettingParser<Cache>() {
            init {
                register("ram-unsafe") { name, url, context -> MapCache(name, mutableMapOf(), context) }
                platformSpecificCacheSettings()
            }
        }

        override fun invoke(name: String, context: SettingContext): Cache {
            return parse(name, url, context)
        }
    }


    /**
     * Returns a value of type T from the cache.
     *
     * @param key The key that will be used to retrieve the value
     * @param serializer The serializer that will be used to turn the raw serialized data from the cache into T.
     * @return An instance of T, or null if the key did not exist in the cache.
     */
    public suspend fun <T> get(key: String, serializer: KSerializer<T>): T?

    /**
     * Sets the instance of T provided into the cache under the key provided. If the key already exists the existing data will be overwritten.
     * You can optionally provide an expiration time on the key. After that duration the key will automatically be removed.
     *
     * @param key The key that will be used when placing the value into the database.
     * @param value The instance of T that you wish to store into the cache.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    public suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)

    /**
     * Sets the instance of T provided into the cache under the key provided. If the key already exists then the incoming value will not be added to the cache.
     * You can optionally provide an expiration time on the key. After that duration the key will automatically be removed.
     *
     * @param key The key that will be used when placing the value into the database.
     * @param value The instance of T that you wish to store into the cache.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    public suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration? = null
    ): Boolean

    /**
     * Will modify an existing value in the cache. If the key does not exist and the modification still returns a value
     * then the new value will be inserted into the cache.
     *
     * @param key The key that will be used when modifying the value into the database.
     * @param serializer The serializer that will be used to turn the instance of T into serialized data to be stored in the cache.
     * @param maxTries How many times it will attempt to make the modification to the cache.
     * @param timeToLive  (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     * @param modification A lambda that takes in a nullable T and returns a nullable T. If a non null value is returned it will be set in the cache using the key. If a null value is returned the key will be removed from the cache.
     */
    public suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if (current == get(key, serializer)) {
                if (new != null)
                    set(key, new, serializer, timeToLive)
                else
                    remove(key)
                return true
            }
        }
        return false
    }


    /**
     * Updates the value under key by adding value to the numerical value stored in the cache.
     *
     * @param key The key that will be used when updating the value into the database.
     * @param value The Int you wish to add to the value already in the cache.
     * @param timeToLive (Optional) The expiration time to be set on for the key in the cache. If no value is provided the key will have no expiration time.
     */
    public suspend fun add(key: String, value: Int, timeToLive: Duration? = null)

    /**
     * Removes a single key from cache. If the key didn't exist, nothing will happen.
     *
     * @param key The key that will be removed from the cache.
     */
    public suspend fun remove(key: String)

    /**
     * Will attempt inserting data into the cache to confirm that the connection is alive and available.
     */
    override suspend fun healthCheck(): HealthStatus {
        return try {
            set("health-check-test-key", Clock.System.now())
            // We check if the write occurred recently to ensure we're not just seeing stale information
            if (get<Instant>("health-check-test-key").let { it != null && it > Clock.System.now().minus(10.seconds) }) {
                HealthStatus(HealthStatus.Level.OK)
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

