package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * Redis implementation of PubSub (Publish/Subscribe) messaging using Lettuce client.
 *
 * Provides distributed pub/sub messaging with:
 * - **Real-time messaging**: Low-latency message delivery via Redis Pub/Sub
 * - **Multiple subscribers**: Many consumers can listen to same channel
 * - **Reactive streams**: Uses Reactor Flux for backpressure-aware streaming
 * - **Automatic JSON serialization**: Type-safe message serialization
 * - **Channel multiplexing**: Single connection supports multiple channels
 * - **At-most-once delivery**: Messages not persisted (ephemeral)
 *
 * ## Supported URL Schemes
 *
 * - `redis://host:port` - Standard Redis connection
 * - `redis://host:port/database` - Specific database number
 * - `redis://user:password@host:port` - Authenticated connection
 * - `rediss://host:port` - TLS/SSL connection
 *
 * Format: Same as Redis cache URL schemes
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Local development
 * PubSub.Settings("redis://localhost:6379")
 *
 * // Production with authentication
 * PubSub.Settings("redis://user:password@redis.example.com:6379")
 *
 * // AWS ElastiCache with TLS
 * PubSub.Settings("rediss://master.cache.amazonaws.com:6380")
 *
 * // Using helper function
 * PubSub.Settings.Companion.redis("localhost:6379")
 * ```
 *
 * ## Implementation Notes
 *
 * - **Lettuce reactive**: Uses Lettuce reactive API (built on Project Reactor)
 * - **Separate connections**: One for subscribe, one for publish (Redis requirement)
 * - **Shared observables**: Multiple subscribers share the same Redis subscription
 * - **Backpressure**: Reactor Flux provides backpressure to slow consumers
 * - **Error handling**: Errors printed to stderr (doOnError)
 * - **Channel lifecycle**: Channels auto-subscribe on first collect, auto-unsubscribe when done
 *
 * ## Important Gotchas
 *
 * - **No message persistence**: Messages lost if no subscribers connected
 * - **No delivery guarantees**: At-most-once delivery (fire-and-forget)
 * - **No message history**: New subscribers don't receive past messages
 * - **Pattern subscriptions**: Not implemented (only exact channel names)
 * - **Backpressure limits**: Fast publishers can overwhelm slow subscribers
 * - **Connection stability**: Network issues can drop messages silently
 * - **Ordering**: Message order preserved per channel, but not across channels
 * - **No acknowledgment**: Publishers don't know if anyone received the message
 *
 * ## Use Cases
 *
 * **Good for:**
 * - Real-time notifications (chat, alerts, updates)
 * - Cache invalidation signals
 * - Event broadcasting to multiple services
 * - Live dashboards and monitoring
 * - Coordination signals (e.g., "refresh config")
 *
 * **Avoid for:**
 * - Critical messages requiring delivery guarantees
 * - Message queuing with persistence
 * - Ordered processing across multiple channels
 * - Long-running workflows
 * - Messages that must survive crashes
 *
 * ## Comparison with Redis Streams
 *
 * Redis Pub/Sub is simpler but less reliable than Redis Streams:
 * - **Pub/Sub**: Fire-and-forget, no persistence, instant delivery
 * - **Streams**: Persisted, consumer groups, replayable, at-least-once delivery
 *
 * For critical messaging, consider using Redis Streams or a proper message queue.
 *
 * ## Example Usage
 *
 * ```kotlin
 * val pubsub = PubSub.Settings("redis://localhost:6379")
 *     .invoke("pubsub", context)
 *
 * // Type-safe channel
 * val userChannel = pubsub.get("user-events", User.serializer())
 *
 * // Publisher (fire-and-forget)
 * launch {
 *     userChannel.emit(User(id = "123", name = "Alice"))
 * }
 *
 * // Subscriber (receives all future messages)
 * launch {
 *     userChannel.collect { user ->
 *         println("Received: $user")
 *     }
 * }
 *
 * // String channel (no serialization)
 * val logChannel = pubsub.string("logs")
 * logChannel.emit("Application started")
 * ```
 *
 * @property name Service name for logging/metrics
 * @property context Service context with serializers
 * @property client Lettuce Redis client for connections
 */
public class RedisPubSub(
    override val name: String,
    override val context: SettingContext,
    private val client: RedisClient
) : PubSub {
    private val observables = ConcurrentHashMap<String, Flux<String>>()
    private val subscribeConnection = client.connectPubSub().reactive()
    private val publishConnection = client.connectPubSub().reactive()
    private val json = Json { serializersModule = context.internalSerializersModule }

    public companion object {
        public fun PubSub.Settings.Companion.redis(url: String): PubSub.Settings = PubSub.Settings("redis://$url")
        init {
            PubSub.Settings.register("redis") { name, url, context ->
                RedisPubSub(name, context, RedisClient.create(url))
            }
        }
    }

    private fun key(key: String): Flux<String> = observables.getOrPut(key) {
        val reactive = subscribeConnection
        Flux.usingWhen(
            reactive.subscribe(key).then(Mono.just(reactive)),
            {
                it.observeChannels()
                    .filter { it.channel == key }
            },
            { it.unsubscribe(key) }
        ).map { it.message }
            .doOnError { it.printStackTrace() }
            .share()
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object : PubSubChannel<T> {
            override suspend fun collect(collector: FlowCollector<T>) {
                key(key).map { json.decodeFromString(serializer, it) }.collect { collector.emit(it) }
            }

            override suspend fun emit(value: T) {
                publishConnection.publish(key, json.encodeToString(serializer, value)).awaitFirst()
            }
        }
    }

    override fun string(key: String): PubSubChannel<String> {
        return object : PubSubChannel<String> {
            override suspend fun collect(collector: FlowCollector<String>) {
                key(key).asFlow().collect { collector.emit(it) }
            }

            override suspend fun emit(value: String) {
                publishConnection.publish(key, value).awaitFirst()
            }
        }
    }

}