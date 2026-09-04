package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Flux.usingWhen
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
 * ## Connection Model
 *
 * A single shared pub/sub connection carries every channel. [subscription] builds a per-channel hot
 * [Flux] with [Flux.share], so all collectors of a channel see every message but Redis holds a single
 * SUBSCRIBE for it. [Flux.share] refcounts that upstream: the first collector SUBSCRIBEs, the last
 * one UNSUBSCRIBEs, and a later collector re-SUBSCRIBEs after the refcount drops to zero.
 * [Flux.usingWhen] scopes that SUBSCRIBE/UNSUBSCRIBE to a single resource lifetime.
 *
 * The connection opens lazily on first use and is recreated by [disconnect], so a serverless handler
 * can release the socket before its execution context freezes without permanently disabling the
 * service. Dropping the connection ends any live collectors (their flows complete), which is the
 * intended teardown.
 *
 * __Note on caching:__ [channels] holds one entry per currently-subscribed channel key. Each entry is
 * removed when its last collector leaves (see [subscription]), so a key is only retained while it is
 * actively in use and no accumulation happens across short-lived uses. [disconnect] additionally
 * clears the whole map when it drops the connection.
 *
 * ## Important Gotchas
 *
 * - **No message persistence**: Messages lost if no subscribers connected
 * - **No delivery guarantees**: At-most-once delivery (fire-and-forget)
 * - **No message history**: New subscribers don't receive past messages
 * - **Pattern subscriptions**: Not implemented (only exact channel names)
 * - **Backpressure limits**: Fast publishers can overwhelm slow subscribers; [Flux.share] keeps each
 *   collector independently-buffered, so a slow collector grows heap rather than blocking others
 * - **Connection stability**: Network issues can drop messages silently, though Lettuce reconnects
 *   and replays SUBSCRIBE for every channel still being collected
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
    private val logger = LoggerFactory.getLogger("RedisPubSub")
    private val json = Json { serializersModule = context.internalSerializersModule }

    /**
     * One shared connection for both publishing and subscribing, rebuilt after [disconnect].
     *
     * `var` + `lazy` (rather than a plain `val`) so [disconnect] can drop the built connection and
     * let the next use rebuild it. Volatile because the getter reads it outside [lifecycleLock].
     */
    @Volatile
    private var _connection = lazy { client.connectPubSub() }
    private val connection: StatefulRedisPubSubConnection<String, String> get() = _connection.value

    /**
     * Per-channel hot Flux, created on first use and evicted when its last collector leaves. Internal
     * so tests can assert eviction directly.
     */
    internal val channels = ConcurrentHashMap<String, Flux<String>>()

    /** Guards connection recreation so [disconnect] cannot race a live [connection] use. */
    private val lifecycleLock = Mutex()

    public companion object {
        public fun PubSub.Settings.Companion.redis(url: String): PubSub.Settings = PubSub.Settings("redis://$url")
        init {
            PubSub.Settings.register("redis") { name, url, context ->
                RedisPubSub(name, context, RedisClient.create(url))
            }
        }
    }

    /**
     * The hot Flux for [key]. First collector (per connection) subscribes and the last unsubscribes,
     * thanks to [Flux.share]; re-SUBSCRIBE happens automatically once the refcount returns to zero.
     * [Flux.usingWhen] makes the (un)subscribe a single subscription resource. When the last collector
     * leaves, the entry is evicted so a high cardinality of channel keys does not accumulate without
     * bound.
     */
    private fun subscription(key: String): Flux<String> = channels.computeIfAbsent(key) {
        val reactive = connection.reactive()
        usingWhen(
            reactive.subscribe(key).then(Mono.just(reactive)),
            { it.observeChannels().filter { c -> c.channel == key }.map { c -> c.message } },
            { channels.remove(key); it.unsubscribe(key).then() }
        ).doOnError { e -> logger.error("Redis subscription for channel '$key' failed", e) }
            .share()
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
        override suspend fun collect(collector: FlowCollector<T>): Unit = telemetryTrace("subscribe", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("pubsub.operation"), "subscribe")
            put(TelemetryKey.OfString("messaging.destination"), key)
            put(TelemetryKeys.Messaging.system, "redis")
        }) {
            // Per-message spans created in the collecting coroutine (after asFlow()) so makeCurrent()
            // works correctly and child spans get the right parent.
            subscription(key).asFlow().collect { message ->
                telemetryTrace("receive", attributes = TelemetryAttributes {
                    put(TelemetryKey.OfString("pubsub.operation"), "receive")
                    put(TelemetryKey.OfString("messaging.destination"), key)
                    put(TelemetryKeys.Messaging.system, "redis")
                    put(TelemetryKey.OfLong("message.size"), message.length.toLong())
                }) {
                    collector.emit(decode(message))
                }
            }
        }

        override suspend fun emit(value: T): Unit = telemetryTrace("publish", attributes = TelemetryAttributes {
            put(TelemetryKey.OfString("pubsub.operation"), "publish")
            put(TelemetryKey.OfString("messaging.destination"), key)
            put(TelemetryKeys.Messaging.system, "redis")
        }) { span ->
            val message = encode(value)
            span.enrich(TelemetryAttributes { put(TelemetryKey.OfLong("message.size"), message.length.toLong()) })
            val result = connection.reactive().publish(key, message).awaitFirst()
            span.enrich(TelemetryAttributes { put(TelemetryKey.OfLong("pubsub.subscribers_reached"), result) })
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        channelImpl(key, { json.encodeToString(serializer, it) }, { json.decodeFromString(serializer, it) })

    override fun string(key: String): PubSubChannel<String> =
        channelImpl(key, { it }, { it })

    /** Opens the shared connection. Optional -- every operation opens it lazily. */
    override suspend fun connect() {
        connection
    }

    /**
     * Closes the shared connection, ending any live collectors (their flows complete), and drops the
     * cached per-channel fluxes. Idempotent: repeated calls, or calling it without ever having
     * connected, are a no-op beyond the first.
     *
     * The next use rebuilds the connection, so this does not permanently disable the service. The
     * [client] is left running because it is constructor-injected and may be shared with other
     * services.
     */
    override suspend fun disconnect() {
        lifecycleLock.withLock {
            if (_connection.isInitialized()) _connection.value.close()
            channels.clear()
            _connection = lazy { client.connectPubSub() }
        }
    }

    /**
     * Verifies Redis connectivity and credentials with a non-mutating PING on the shared connection.
     *
     * Overrides the abstraction's default (which PUBLISHes a test message): PING exercises the full
     * connection including AUTH on authenticated/TLS URLs without broadcasting a stray message to
     * subscribers, and a healthy server replies `PONG`.
     */
    override suspend fun healthCheck(): HealthStatus =
        try {
            val reply = telemetryTrace("ping") {
                connection.reactive().ping().awaitFirstOrNull()
            }
            if (reply == "PONG") HealthStatus(HealthStatus.Level.OK)
            else HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Unexpected PING reply: $reply")
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
}