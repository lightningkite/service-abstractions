package com.lightningkite.serviceabstractions.cache

import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.countMetric
import com.lightningkite.serviceabstractions.measure
import com.lightningkite.serviceabstractions.performanceMetric
import com.lightningkite.serviceabstractions.report
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration

/**
 * Automatically track performance for implementations.
 */
public abstract class MetricTrackingCache: Cache {
    private val readMetric = performanceMetric("read")
    private val writeMetric = performanceMetric("write")
    private val modifyFailuresMetric = countMetric("modifyFailures")

    protected abstract suspend fun <T> getInternal(key: String, serializer: KSerializer<T>): T?
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        return readMetric.measure { getInternal(key, serializer) }
    }

    protected abstract suspend fun <T> setInternal(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?)
    override suspend fun <T> set(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ) {
        writeMetric.measure { setInternal(key, value, serializer, timeToLive) }
    }

    protected open suspend fun <T> setIfNotExistsInternal(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        return writeMetric.measure {
            if (getInternal<T>(key, serializer) == null) {
                setInternal(key, value, serializer, timeToLive)
                true
            } else {
                false
            }
        }
    }
    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean {
        return writeMetric.measure {
            setIfNotExistsInternal(key, value, serializer, timeToLive)
        }
    }

    protected abstract suspend fun addInternal(key: String, value: Int, timeToLive: Duration?)
    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        return writeMetric.measure { addInternal(key, value, timeToLive) }
    }

    protected abstract suspend fun removeInternal(key: String)
    override suspend fun remove(key: String) {
        return writeMetric.measure { removeInternal(key) }
    }

    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
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
            modifyFailuresMetric.report()
        }
        return false
    }
}