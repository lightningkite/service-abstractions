package com.lightningkite.services.pubsub

import com.lightningkite.services.SettingContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A local implementation of the PubSub interface.
 */
public class LocalPubSub(
    override val name: String,
    override val context: SettingContext
) : PubSub {
    private val channels = mutableMapOf<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        val existing = channels[key]
        if (existing != null) {
            return existing as PubSubChannel<T>
        }

        val flow = MutableSharedFlow<T>(0)
        val channel = object : PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                flow.collect(collector)
            }

            override suspend fun emit(value: T) {
                flow.emit(value)
            }
        }

        channels[key] = channel
        return channel
    }

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}

/**
 * A debug implementation of the PubSub interface that logs operations.
 */
public class DebugPubSub(
    override val name: String,
    override val context: SettingContext
) : PubSub {
    private val channels = mutableMapOf<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        val existing = channels[key]
        if (existing != null) {
            return existing as PubSubChannel<T>
        }

        println("[DEBUG_PUBSUB] Created channel $key")
        val flow = MutableSharedFlow<T>(0)
        val channel = object : PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                flow.collect(collector)
            }

            override suspend fun emit(value: T) {
                println("[DEBUG_PUBSUB] Emit to channel $key: $value (subscribers: ${flow.subscriptionCount.value})")
                flow.emit(value)
            }
        }

        channels[key] = channel
        return channel
    }

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}