package com.lightningkite.services.pubsub

import com.lightningkite.services.SettingContext
import com.lightningkite.services.countMetric
import com.lightningkite.services.increment
import com.lightningkite.services.measure
import com.lightningkite.services.performanceMetric
import com.lightningkite.services.report
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Automatically track performance for pub-sub service implementations.
 */
public abstract class MetricTrackingPubSub(override val context: SettingContext) : PubSub {
    private val getChannelMetric = performanceMetric("getChannel")
    private val emitMetric = performanceMetric("emit")
    private val failureMetric = countMetric("failures")

    /**
     * Internal implementation of get that will be measured for performance.
     */
    protected abstract fun <T> getInternal(key: String, serializer: KSerializer<T>): PubSubChannel<T>

    /**
     * Gets a channel for a specific key with a serializer, with performance tracking.
     */
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        val baseChannel = getInternal(key, serializer)
        return object : PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                baseChannel.collect(collector)
            }

            override suspend fun emit(value: T) {
                try {
                    emitMetric.measure { baseChannel.emit(value) }
                } catch (e: Exception) {
                    failureMetric.increment()
                    throw e
                }
            }
        }
    }

    /**
     * Internal implementation of string that will be measured for performance.
     */
    protected open fun stringInternal(key: String): PubSubChannel<String> {
        return getInternal(key, String.serializer())
    }

    /**
     * Gets a string channel for a specific key, with performance tracking.
     */
    override fun string(key: String): PubSubChannel<String> {
        val baseChannel = stringInternal(key)
        return object : PubSubChannel<String> {
            override suspend fun collect(collector: FlowCollector<String>) {
                baseChannel.collect(collector)
            }

            override suspend fun emit(value: String) {
                try {
                    emitMetric.measure { baseChannel.emit(value) }
                } catch (e: Exception) {
                    failureMetric.increment()
                    throw e
                }
            }
        }
    }
}