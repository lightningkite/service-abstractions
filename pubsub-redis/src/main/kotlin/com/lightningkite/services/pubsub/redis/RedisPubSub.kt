package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.SettingContext
import com.lightningkite.services.otel.OpenTelemetrySub
import com.lightningkite.services.otel.get
import com.lightningkite.services.otel.span
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.resource.ClientResources
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.lettuce.v5_1.LettuceTelemetry
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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
/**
 * Stateless Redis PubSub implementation suitable for serverless environments.
 *
 * Each call to [get] creates a fresh subscription - no caching is done at the service level.
 * This ensures that different Lambda invocations don't share stale subscriptions and
 * prevents memory leaks from unbounded caches.
 *
 * Redis natively handles pub/sub fan-out, so multiple subscribers (even across different
 * processes/Lambda instances) will all receive published messages.
 */
public class RedisPubSub(
    override val name: String,
    override val context: SettingContext,
    private val client: RedisClient
) : PubSub {
    private val otel: OpenTelemetrySub? = context.openTelemetry?.get("pubsub-redis")
    private val redisPubSubLogger = LoggerFactory.getLogger("RedisPubSub")
    private val json = Json { serializersModule = context.internalSerializersModule }

    // Shared publish connection. Lettuce connections are thread-safe and pipeline commands.
    // Opening a connection per emit caused TLS handshake storms that pegged Netty event loops.
    private val publishConnection: StatefulRedisPubSubConnection<String, String> by lazy {
        client.connectPubSub()
    }

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

    /**
     * Creates a fresh Redis subscription for the given key.
     * No caching - each call creates a new subscription that will be cleaned up
     * when the collector completes or cancels.
     */
    private fun createSubscription(key: String): Flux<String> {
        val statefulConnection = client.connectPubSub()
        val reactiveConnection = statefulConnection.reactive()
        return Flux.usingWhen<String, io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands<String, String>>(
            reactiveConnection.subscribe(key).then(Mono.just(reactiveConnection)),
            { conn ->
                conn.observeChannels()
                    .filter { it.channel == key }
                    .map { it.message }
            },
            { _ ->
                // Cleanup: unsubscribe and close the connection
                reactiveConnection.unsubscribe(key).doFinally { statefulConnection.close() }.then()
            }
        ).doOnError { error ->
            redisPubSubLogger.error("Error in Redis subscription for channel $key", error)
        }
    }

    /**
     * Builds a [PubSubChannel] backed by Redis pub/sub, using the given codec to
     * translate between [T] and the raw on-the-wire Redis string payload.
     *
     * @param key Redis channel name to subscribe to and publish on.
     * @param encode Serializes a value of [T] into the string payload published to Redis.
     * @param decode Parses an incoming Redis message back into a value of [T].
     */
    private fun <T> channelImpl(
        key: String,
        encode: (T) -> String,
        decode: (String) -> T,
    ): PubSubChannel<T> = object : PubSubChannel<T> {
        override suspend fun collect(collector: FlowCollector<T>): Unit = otel.span("pubsub.subscribe", configure = {
            setSpanKind(SpanKind.CONSUMER)
            setAttribute("pubsub.operation", "subscribe")
            setAttribute("messaging.destination", key)
            setAttribute("messaging.system", "redis")
        }) {
            // Create fresh subscription - no caching for serverless compatibility
            // Per-message spans created in the coroutine context (after asFlow()) so
            // makeCurrent() works correctly and child spans get the right parent.
            createSubscription(key).asFlow().collect { message ->
                otel.span("pubsub.receive", configure = {
                    setSpanKind(SpanKind.CONSUMER)
                    setAttribute("pubsub.operation", "receive")
                    setAttribute("messaging.destination", key)
                    setAttribute("messaging.system", "redis")
                    setAttribute("message.size", message.length.toLong())
                }) {
                    collector.emit(decode(message))
                }
            }
        }

        override suspend fun emit(value: T): Unit = otel.span("pubsub.publish", configure = {
            setSpanKind(SpanKind.PRODUCER)
            setAttribute("pubsub.operation", "publish")
            setAttribute("messaging.destination", key)
            setAttribute("messaging.system", "redis")
        }) { span ->
            val message = encode(value)
            span?.setAttribute("message.size", message.length.toLong())
            val result = publishConnection.reactive().publish(key, message).awaitFirst()
            span?.setAttribute("pubsub.subscribers_reached", result)
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        channelImpl(key, { json.encodeToString(serializer, it) }, { json.decodeFromString(serializer, it) })

    override fun string(key: String): PubSubChannel<String> =
        channelImpl(key, { it }, { it })

}