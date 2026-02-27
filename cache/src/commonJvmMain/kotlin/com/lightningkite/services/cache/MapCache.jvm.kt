package com.lightningkite.services.cache

import com.lightningkite.services.SettingContext
import com.lightningkite.services.default
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.*

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class MapCache actual constructor(
    name: String,
    context: SettingContext,
) : Cache {
    actual override val name: String = name
    actual override val context: SettingContext = context
    private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

    @Suppress("UNCHECKED_CAST")
    actual override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return instrumentedGet(context, key) {
            entries[key]?.takeIf { it.expires == null || it.expires > Clock.default().now() }?.value as? T
        }
    }

    actual override suspend fun <T> set(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ) {
        instrumentedSet<T>(context, key, timeToLive) {
            entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
        }
    }

    public actual fun clear() {
        entries.clear()
    }

    actual override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
        return instrumentedSetIfNotExists(context, key, timeToLive) {
            // We can't prove success using the response from the compute function, so we must manually handle it.
            var success = false
            val clock = Clock.default()
            entries.compute(key) { _, existing ->
                if (existing == null || (existing.expires != null && existing.expires <= clock.now())) {
                    success = true
                    Entry(value, timeToLive?.let { clock.now() + it })
                } else existing
            }
            success
        }
    }

    actual override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        instrumentedAdd(context, key, value, timeToLive) {
            val clock = Clock.default()
            entries.compute(key) { _, existing ->
                val entry = existing?.takeIf { it.expires == null || it.expires > clock.now() }
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
                Entry(new, timeToLive?.let { clock.now() + it })
            }
        }
    }

    actual override suspend fun remove(key: String) {
        instrumentedRemove(context, key) {
            entries.remove(key)
        }
    }

    @Suppress("UNCHECKED_CAST")
    actual override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?,
    ): Boolean {
        return instrumentedModify<T>(context, key, maxTries, timeToLive) {
            entries.compute(key) { _, existing ->
                val current = existing?.value as? T 
                val new = modification(current)
                if (new != null) {
                    Entry(new, existing?.expires)
                } else {
                    null
                }
            }
            true
        }
    }

    public actual class Entry actual constructor(value: Any?, expires: Instant?) {
        public val value: Any? = value
        public val expires: Instant? = expires
    }
}