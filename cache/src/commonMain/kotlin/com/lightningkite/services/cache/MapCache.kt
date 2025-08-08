package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import com.lightningkite.services.default
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration

/**
 * A cache backed by a simple map.
 * You should select a map implementation that matches your concurrency needs.
 */
public open class MapCache(
    public val entries: MutableMap<String, Entry>,
    override val context: SettingContext,
) : MetricTrackingCache() {
    private val serializersModule: SerializersModule get() = context.internalSerializersModule
    public data class Entry(val value: Any?, val expires: Instant? = null)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> getInternal(key: String, serializer: KSerializer<T>): T? {
        return entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }?.value as? T
    }

    override suspend fun <T> setInternal(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
    }

    public fun clearInternal() {
        entries.clear()
    }

    override suspend fun <T> setIfNotExistsInternal(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        if (entries[key] == null) {
            entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
            return true
        }
        return false
    }

    override suspend fun addInternal(key: String, value: Int, timeToLive: Duration?): Unit {
        val entry = entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }
        val current = entry?.value
        val new = when (current) {
            is Byte -> (current + value).toByte()
            is Short -> (current + value).toShort()
            is Int -> (current + value)
            is Long -> (current + value)
            is Float -> (current + value)
            is Double -> (current + value)
            else -> value
        }
        entries[key] = Entry(new, timeToLive?.let { Clock.default().now() + it })
    }

    override suspend fun removeInternal(key: String): Unit {
        entries.remove(key)
    }
}