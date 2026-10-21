package com.lightningkite.services.pubsub.redis

import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.telemetry.telemetryTrace
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.reactive.RedisPubSubReactiveCommands
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
import reactor.core.publisher.Mono
import java.util.concurrent.CompletableFuture
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
 * Each instance holds two connections:
 * - A shared **subscribe** connection carries every SUBSCRIBE/UNSUBSCRIBE and receives every message,
 *   for all channels. Each channel has one [Subscription] counting its live collectors, so all
 *   collectors of a channel see every message while Redis holds a single SUBSCRIBE for it: the first
 *   collector SUBSCRIBEs, the last one UNSUBSCRIBEs.
 * - A **publish** connection sends every PUBLISH and never subscribes, so Redis never delivers a
 *   message back to the connection that published it. Valkey 9.0.0-9.0.5 tears the RESP3 push frame in
 *   exactly that case for messages of 16 KiB or more (valkey-io/valkey#4231, fixed in 9.0.6 and 9.1.2).
 *   Lettuce then reads the payload as the PUBLISH reply and never matches another SUBSCRIBE
 *   confirmation on that connection, so every later subscription silently hangs.
 *
 * Both connections open lazily on first use and are recreated by [disconnect], so a serverless
 * handler can release the sockets before its execution context freezes without permanently disabling
 * the service. Dropping the subscribe connection ends any live collectors (their flows complete),
 * which is the intended teardown.
 *
 * __Note on caching:__ [channels] holds one entry per currently-subscribed channel key. Each entry is
 * removed when its last collector leaves (see [release]), so a key is only retained while it is
 * actively in use and no accumulation happens across short-lived uses. [disconnect] additionally
 * clears the whole map when it drops the connections.
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
     * The shared subscribe connection, rebuilt after [disconnect]. Never used to publish; see
     * [publishConnection].
     *
     * `var` + `lazy` (rather than a plain `val`) so [disconnect] can drop the built connection and
     * let the next use rebuild it. Volatile because the getter reads it outside [lifecycleLock].
     */
    @Volatile
    private var _subscribeConnection = lazy { client.connectPubSub() }
    private val subscribeConnection: StatefulRedisPubSubConnection<String, String>
        get() = _subscribeConnection.value

    /**
     * Sends every PUBLISH, rebuilt after [disconnect]. A plain connection that never subscribes, so a
     * message is never delivered back to the connection that published it (see the class docs).
     */
    @Volatile
    private var _publishConnection = lazy { client.connect() }
    private val publishConnection: StatefulRedisConnection<String, String>
        get() = _publishConnection.value

    /**
     * Live subscriptions by channel key. Read freely (tests assert eviction directly), but every
     * mutation goes through [ConcurrentHashMap.compute] on that key, together with the SUBSCRIBE or
     * UNSUBSCRIBE it implies; see [acquire] and [release].
     *
     * Must stay a [ConcurrentHashMap]: its `compute` applies the function exactly once while holding
     * the key's lock. The default [java.util.concurrent.ConcurrentMap.compute] may retry the function,
     * which would send SUBSCRIBE twice and miscount collectors.
     */
    internal val channels = ConcurrentHashMap<String, Subscription>()

    /** Guards connection recreation so [disconnect] cannot race a live connection use. */
    private val lifecycleLock = Mutex()

    /**
     * One channel's SUBSCRIBE on one specific subscribe connection, shared by all of that channel's
     * collectors.
     *
     * Constructing it sends the SUBSCRIBE, so it is only constructed inside `channels.compute(key)`.
     * It is pinned to [connection] so that a collector outliving a [disconnect] releases against the
     * dead connection and never UNSUBSCRIBEs the replacement.
     */
    internal inner class Subscription(
        val key: String,
        val connection: StatefulRedisPubSubConnection<String, String>,
    ) {
        /** Live collectors. Only read or written inside `channels.compute(key)`, which serializes them. */
        var collectors = 0

        /** Completes when Redis confirms the SUBSCRIBE. */
        val confirmed: CompletableFuture<Void> = connection.async().subscribe(key).toCompletableFuture()
            .whenComplete { _, e -> if (e != null) logger.error("Redis subscription for channel '$key' failed", e) }

        /** This channel's messages, fanned out to its collectors with one listener on the connection. */
        val messages: Flux<String> = connection.commands(RedisPubSubReactiveCommands.factory())
            .observeChannels()
            .filter { it.channel == key }
            .map { it.message }
            .share()
    }

    public companion object {
        public fun PubSub.Settings.Companion.redis(url: String): PubSub.Settings = PubSub.Settings("redis://$url")
        init {
            PubSub.Settings.register("redis") { name, url, context ->
                RedisPubSub(name, context, RedisClient.create(url))
            }
        }
    }

    /**
     * Registers a collector on [key], sending SUBSCRIBE if it is the channel's first. Every [acquire]
     * must be paired with a [release].
     *
     * SUBSCRIBE and UNSUBSCRIBE are sent (not merely built) inside `compute` on the channel's key.
     * Commands on one connection execute in the order sent, so Redis' subscription state always
     * matches the collector count, even when a channel's last collector leaves while a new one
     * arrives. Different channels need no ordering against each other, so they do not contend.
     */
    private fun acquire(key: String): Subscription {
        val connection = subscribeConnection
        return channels.compute(key) { _, existing ->
            val subscription = if (existing != null && existing.connection === connection) existing
            else Subscription(key, connection)
            subscription.collectors++
            subscription
        }!!
    }

    /** Unregisters a collector, sending UNSUBSCRIBE and evicting the entry if it was the last. */
    private fun release(subscription: Subscription) {
        channels.compute(subscription.key) { _, existing ->
            if (--subscription.collectors > 0) return@compute existing
            // A subscription from before a disconnect belongs to a closed connection: nothing to undo.
            if (subscription.connection.isOpen) {
                subscription.connection.async().unsubscribe(subscription.key).whenComplete { _, e ->
                    if (e != null) logger.warn("Redis unsubscribe for channel '${subscription.key}' failed", e)
                }
            }
            // Evict only this subscription; the key may already belong to a newer connection's entry.
            if (existing === subscription) null else existing
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
        override suspend fun collect(collector: FlowCollector<T>) {
            val subscription = acquire(key)
            try {
                // Listen before the SUBSCRIBE is confirmed, so nothing published in between is missed.
                // Merging the confirmation in surfaces a failed SUBSCRIBE as this flow's error.
                // suppressCancel: the confirmation is shared with the channel's other collectors.
                subscription.messages
                    .mergeWith(Mono.fromFuture(subscription.confirmed, true).then(Mono.empty()))
                    .asFlow()
                    .collect { message ->
                        // Per-message spans created in the collecting coroutine (after asFlow()) so
                        // makeCurrent() works correctly and child spans get the right parent.
                        telemetryTrace("receive", attributes = TelemetryAttributes {
                            put(TelemetryKeys.Messaging.system, "redis")
                            put(TelemetryKeys.Messaging.destinationName, key)
                            put(TelemetryKeys.Messaging.operationName, "receive")
                            put(TelemetryKeys.Messaging.operationType, "process")
                            put(TelemetryKeys.Messaging.messageBodySize, message.encodeToByteArray().size.toLong())
                        }) {
                            // One undecodable payload must not end the subscription: whatever is
                            // collecting (e.g. a WebSocket) would otherwise be torn down with it.
                            val value = try {
                                decode(message)
                            } catch (e: Exception) {
                                logger.warn("Dropped undecodable message on channel '$key': ${message.take(200)}", e)
                                return@telemetryTrace
                            }
                            collector.emit(value)
                        }
                    }
            } finally {
                release(subscription)
            }
        }

        override suspend fun emit(value: T): Unit = telemetryTrace("publish", attributes = TelemetryAttributes {
            put(TelemetryKeys.Messaging.system, "redis")
            put(TelemetryKeys.Messaging.destinationName, key)
            put(TelemetryKeys.Messaging.operationName, "publish")
            put(TelemetryKeys.Messaging.operationType, "send")
        }) { span ->
            val message = encode(value)
            span.enrich(TelemetryAttributes { put(TelemetryKeys.Messaging.messageBodySize, message.encodeToByteArray().size.toLong()) })
            val result = publishConnection.commands(RedisReactiveCommands.factory()).publish(key, message).awaitFirst()
            span.enrich(TelemetryAttributes { put(PubSub.TelemetryKeys.subscribersReached, result) })
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> =
        channelImpl(key, { json.encodeToString(serializer, it) }, { json.decodeFromString(serializer, it) })

    override fun string(key: String): PubSubChannel<String> =
        channelImpl(key, { it }, { it })

    /** Opens both connections. Optional -- every operation opens what it needs lazily. */
    override suspend fun connect() {
        subscribeConnection
        publishConnection
    }

    /**
     * Closes both connections, ending any live collectors (their flows complete), and drops the
     * cached subscriptions. Idempotent: repeated calls, or calling it without ever having connected,
     * are a no-op beyond the first.
     *
     * The next use rebuilds the connections, so this does not permanently disable the service. The
     * [client] is left running because it is constructor-injected and may be shared with other
     * services.
     */
    override suspend fun disconnect() {
        lifecycleLock.withLock {
            if (_subscribeConnection.isInitialized()) _subscribeConnection.value.close()
            if (_publishConnection.isInitialized()) _publishConnection.value.close()
            channels.clear()
            _subscribeConnection = lazy { client.connectPubSub() }
            _publishConnection = lazy { client.connect() }
        }
    }

    /**
     * Verifies Redis connectivity and credentials with a non-mutating PING on each connection.
     *
     * Overrides the abstraction's default (which PUBLISHes a test message): PING exercises the full
     * connection including AUTH on authenticated/TLS URLs without broadcasting a stray message to
     * subscribers, and a healthy server replies `PONG`. Both connections are checked because a
     * subscribe connection that has stopped answering breaks live updates while publishing still works.
     */
    override suspend fun healthCheck(): HealthStatus =
        try {
            val replies = telemetryTrace("ping") {
                mapOf(
                    "subscribe" to subscribeConnection.commands(RedisPubSubReactiveCommands.factory()).ping().awaitFirstOrNull(),
                    "publish" to publishConnection.commands(RedisReactiveCommands.factory()).ping().awaitFirstOrNull(),
                )
            }
            val unexpected = replies.filterValues { it != "PONG" }
            if (unexpected.isEmpty()) HealthStatus(HealthStatus.Level.OK)
            else HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = unexpected.entries.joinToString { "Unexpected PING reply on ${it.key} connection: ${it.value}" },
            )
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
}
