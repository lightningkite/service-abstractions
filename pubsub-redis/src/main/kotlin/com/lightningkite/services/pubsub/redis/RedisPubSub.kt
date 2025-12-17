package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry
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
    private val tracer: Tracer? = context.openTelemetry?.getTracer("pubsub-redis")
    private val observables = ConcurrentHashMap<String, Flux<String>>()
    private val subscribeConnection = client.connectPubSub().reactive()
    private val publishConnection = client.connectPubSub().reactive()
    private val json = Json { serializersModule = context.internalSerializersModule }

    public companion object {
        public fun PubSub.Settings.Companion.redis(url: String): PubSub.Settings = PubSub.Settings("redis://$url")
        init {
            PubSub.Settings.register("redis") { name, url, context ->
                val telemetry = context.openTelemetry?.let { LettuceTelemetry.create(it) }
                val clientResources = telemetry?.let {
                    ClientResources.builder()
                        .tracing(it.newTracing())
                        .build()
                } ?: ClientResources.create()

                val client = RedisClient.create(clientResources, url)
                RedisPubSub(name, context, client)
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
                val span = tracer?.spanBuilder("pubsub.subscribe")
                    ?.setSpanKind(SpanKind.CONSUMER)
                    ?.setAttribute("pubsub.operation", "subscribe")
                    ?.setAttribute("pubsub.channel", key)
                    ?.setAttribute("pubsub.system", "redis")
                    ?.startSpan()

                try {
                    val scope = span?.makeCurrent()
                    try {
                        key(key).map { message ->
                            val receiveSpan = tracer?.spanBuilder("pubsub.receive")
                                ?.setSpanKind(SpanKind.CONSUMER)
                                ?.setAttribute("pubsub.operation", "receive")
                                ?.setAttribute("pubsub.channel", key)
                                ?.setAttribute("pubsub.system", "redis")
                                ?.setAttribute("message.size", message.length.toLong())
                                ?.startSpan()

                            try {
                                val decoded = json.decodeFromString(serializer, message)
                                receiveSpan?.setStatus(StatusCode.OK)
                                decoded
                            } catch (e: Exception) {
                                receiveSpan?.setStatus(StatusCode.ERROR, "Failed to deserialize message: ${e.message}")
                                receiveSpan?.recordException(e)
                                throw e
                            } finally {
                                receiveSpan?.end()
                            }
                        }.collect { collector.emit(it) }
                        span?.setStatus(StatusCode.OK)
                    } finally {
                        scope?.close()
                    }
                } catch (e: Exception) {
                    span?.setStatus(StatusCode.ERROR, "Failed to subscribe: ${e.message}")
                    span?.recordException(e)
                    throw e
                } finally {
                    span?.end()
                }
            }

            override suspend fun emit(value: T) {
                val span = tracer?.spanBuilder("pubsub.publish")
                    ?.setSpanKind(SpanKind.PRODUCER)
                    ?.setAttribute("pubsub.operation", "publish")
                    ?.setAttribute("pubsub.channel", key)
                    ?.setAttribute("pubsub.system", "redis")
                    ?.startSpan()

                try {
                    val scope = span?.makeCurrent()
                    try {
                        val message = json.encodeToString(serializer, value)
                        span?.setAttribute("message.size", message.length.toLong())
                        val result = publishConnection.publish(key, message).awaitFirst()
                        span?.setAttribute("pubsub.subscribers_reached", result)
                        span?.setStatus(StatusCode.OK)
                    } finally {
                        scope?.close()
                    }
                } catch (e: Exception) {
                    span?.setStatus(StatusCode.ERROR, "Failed to publish: ${e.message}")
                    span?.recordException(e)
                    throw e
                } finally {
                    span?.end()
                }
            }
        }
    }

    override fun string(key: String): PubSubChannel<String> {
        return object : PubSubChannel<String> {
            override suspend fun collect(collector: FlowCollector<String>) {
                val span = tracer?.spanBuilder("pubsub.subscribe")
                    ?.setSpanKind(SpanKind.CONSUMER)
                    ?.setAttribute("pubsub.operation", "subscribe")
                    ?.setAttribute("pubsub.channel", key)
                    ?.setAttribute("pubsub.system", "redis")
                    ?.startSpan()

                try {
                    val scope = span?.makeCurrent()
                    try {
                        key(key).asFlow().collect { message ->
                            val receiveSpan = tracer?.spanBuilder("pubsub.receive")
                                ?.setSpanKind(SpanKind.CONSUMER)
                                ?.setAttribute("pubsub.operation", "receive")
                                ?.setAttribute("pubsub.channel", key)
                                ?.setAttribute("pubsub.system", "redis")
                                ?.setAttribute("message.size", message.length.toLong())
                                ?.startSpan()

                            try {
                                receiveSpan?.setStatus(StatusCode.OK)
                                collector.emit(message)
                            } finally {
                                receiveSpan?.end()
                            }
                        }
                        span?.setStatus(StatusCode.OK)
                    } finally {
                        scope?.close()
                    }
                } catch (e: Exception) {
                    span?.setStatus(StatusCode.ERROR, "Failed to subscribe: ${e.message}")
                    span?.recordException(e)
                    throw e
                } finally {
                    span?.end()
                }
            }

            override suspend fun emit(value: String) {
                val span = tracer?.spanBuilder("pubsub.publish")
                    ?.setSpanKind(SpanKind.PRODUCER)
                    ?.setAttribute("pubsub.operation", "publish")
                    ?.setAttribute("pubsub.channel", key)
                    ?.setAttribute("pubsub.system", "redis")
                    ?.setAttribute("message.size", value.length.toLong())
                    ?.startSpan()

                try {
                    val scope = span?.makeCurrent()
                    try {
                        val result = publishConnection.publish(key, value).awaitFirst()
                        span?.setAttribute("pubsub.subscribers_reached", result)
                        span?.setStatus(StatusCode.OK)
                    } finally {
                        scope?.close()
                    }
                } catch (e: Exception) {
                    span?.setStatus(StatusCode.ERROR, "Failed to publish: ${e.message}")
                    span?.recordException(e)
                    throw e
                } finally {
                    span?.end()
                }
            }
        }
    }

}