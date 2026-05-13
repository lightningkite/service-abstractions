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
        assertValidTtl(timeToLive)
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
        assertValidTtl(timeToLive)
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

    private suspend fun add(key: String, value: Long, default: Number, timeToLive: Duration?): Number {
        assertValidTtl(timeToLive)
        return instrumentedAdd(context, key, value, timeToLive) {
            val clock = Clock.default()
            val r = entries.compute(key) { _, existing ->
                val entry = existing?.takeIf { it.expires == null || it.expires > clock.now() }
                val new = when (val current = entry?.value) {
                    is Byte -> (current + value).toByte()
                    is Short -> (current + value).toShort()
                    is Int -> (current + value).toInt()
                    is Long -> (current + value)
                    is Float -> (current + value)
                    is Double -> (current + value)
                    else -> default   // explicitly use the type provided
                }
                Entry(new, timeToLive?.let { clock.now() + it })
            } ?: throw IllegalStateException("Could not modify entry correctly")
            r.value as Number
        }
    }

    actual override suspend fun add(key: String, value: Long, timeToLive: Duration?): Long =
        add(key, value, default = value, timeToLive).toLong()

    actual override suspend fun add(key: String, value: Int, timeToLive: Duration?): Int =
        add(key, value = value.toLong(), default = value, timeToLive).toInt()

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
        assertValidTtl(timeToLive)
        return instrumentedModify<T>(context, key, maxTries, timeToLive) {
            // CAS loop: read current value, compute new value, atomically swap only if unchanged.
            // Retries up to maxTries times on concurrent modification so the modification function
            // always operates on the most recent value.
            val clock = Clock.default()
            repeat(maxTries) {
                val existing = entries[key]?.takeIf { it.expires == null || it.expires > clock.now() }
                val current = existing?.value as? T
                val new = modification(current)
                val newEntry = new?.let { Entry(it, timeToLive?.let { clock.now() + it } ?: existing?.expires) }
                val swapped: Boolean
                if (newEntry != null) {
                    // Replace only if the stored entry is still the one we read.
                    var cas = false
                    entries.compute(key) { _, cur ->
                        if (cur === existing) { cas = true; newEntry } else cur
                    }
                    swapped = cas
                } else {
                    // new == null: delete only if value hasn't changed since we read it.
                    swapped = if (existing != null) entries.remove(key, existing) else !entries.containsKey(key)
                }
                if (swapped) return@instrumentedModify true
            }
            false
        }
    }

    public actual class Entry actual constructor(value: Any?, expires: Instant?) {
        public val value: Any? = value
        public val expires: Instant? = expires
    }
}