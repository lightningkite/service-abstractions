package com.lightningkite.services.cache

import com.lightningkite.services.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

//TODO: Using a top level Mutex for write locking is not the most efficient method but it will work.
// It's best to replace with platform specific implementations.

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
public actual class MapCache actual constructor(name: String, context: SettingContext) :
    Cache {
    actual override val name: String = name
    actual override val context: SettingContext = context
    private val entries = mutableMapOf<String, Entry>()
    private val mutex = Mutex()

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
            mutex.withLock {
                entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
            }
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
            mutex.withLock {
                val existing = entries[key]
                // Check if key doesn't exist OR if it exists but has expired
                if (existing == null || (existing.expires != null && existing.expires <= Clock.default().now())) {
                    entries[key] = Entry(value, timeToLive?.let { Clock.default().now() + it })
                    true
                } else {
                    false
                }
            }
        }
    }

    actual override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        instrumentedAdd(context, key, value, timeToLive) {
            mutex.withLock {
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
        }
    }

    actual override suspend fun remove(key: String) {
        instrumentedRemove(context, key) {
            mutex.withLock {
                entries.remove(key)
            }
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
            mutex.withLock {
                val existing = entries[key] as? T
                val new = modification(existing)

                if (new != null) {
                    entries[key] = Entry(new, timeToLive?.let { Clock.default().now() + it })
                } else {
                    entries.remove(key)
                }

                true
            }
        }
    }

    public actual class Entry actual constructor(value: Any?, expires: Instant?) {
        public val value: Any? = value
        public val expires: Instant? = expires
    }
}