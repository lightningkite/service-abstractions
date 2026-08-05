package com.lightningkite.services.pubsub

import com.lightningkite.services.ConcurrentMutableMap
import com.lightningkite.services.SettingContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The number of not-yet-collected values a channel will hold before it starts dropping the
 * oldest ones. See the comment on [MutableSharedFlow] construction below for why this exists.
 */
private const val CHANNEL_BUFFER_CAPACITY: Int = 64

/**
 * A local implementation of the PubSub interface.
 */
public class LocalPubSub(
    override val name: String,
    override val context: SettingContext,
) : PubSub {
    private val channels = ConcurrentMutableMap<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        // computeIfAbsent evaluates its factory atomically with respect to other callers racing
        // on the same key, so two threads requesting the same channel for the first time can
        // never construct two divergent flows and silently never see each other's messages.
        channels.computeIfAbsent(key) {
            val flow = MutableSharedFlow<T>(
                replay = 0,
                extraBufferCapacity = CHANNEL_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            object : PubSubChannel<T> {
                override suspend fun collect(collector: FlowCollector<T>) {
                    flow.collect(collector)
                }

                override suspend fun emit(value: T) {
                    flow.emit(value)
                }
            }
        } as PubSubChannel<T>

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}

/**
 * A debug implementation of the PubSub interface that logs operations.
 */
public class DebugPubSub(
    override val name: String,
    override val context: SettingContext,
) : PubSub {
    private val channels = ConcurrentMutableMap<String, PubSubChannel<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        channels.computeIfAbsent(key) {
            println("[DEBUG_PUBSUB] Created channel $it")
            val flow = MutableSharedFlow<T>(
                replay = 0,
                extraBufferCapacity = CHANNEL_BUFFER_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
            object : PubSubChannel<T> {
                override suspend fun collect(collector: FlowCollector<T>) {
                    flow.collect(collector)
                }

                override suspend fun emit(value: T) {
                    println("[DEBUG_PUBSUB] Emit to channel $it: $value (subscribers: ${flow.subscriptionCount.value})")
                    flow.emit(value)
                }
            }
        } as PubSubChannel<T>

    override fun string(key: String): PubSubChannel<String> = get(key, String.serializer())
}

