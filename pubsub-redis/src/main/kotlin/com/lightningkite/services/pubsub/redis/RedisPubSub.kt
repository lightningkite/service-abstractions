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
 * Redis implementation of PubSub using Lettuce.
 *
 * One shared pub/sub connection carries every channel. [subscription] builds a per-channel hot
 * [Flux] with [Flux.share], so all collectors of a channel see every message but Redis holds a single
 * SUBSCRIBE for it. [Flux.share] refcounts that upstream: the first collector SUBSCRIBEs, the last
 * one UNSUBSCRIBEs, and a later collector re-SUBSCRIBEs after the refcount drops to zero.
 * [Flux.usingWhen] scopes that SUBSCRIBE/UNSUBSCRIBE to a single __resource__ lifetime.
 *
 * The connection opens lazily on first use and is recreated by [disconnect], so a serverless handler
 * can release the socket before its execution context freezes. Dropping the connection ends any
 * live collectors (their flows complete), which is the intended teardown.
 *
 * __Note on caching:__ [channels] holds one entry per currently-subscribed channel key. Each entry is
 * removed when its last collector leaves (see [subscription]), so a key is only retained while it is
 * actively in use and no accumulation happens across short-lived uses. [disconnect] additionally
 * clears the whole map when it drops the connection.
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
    private val otel: OpenTelemetrySub? = context.openTelemetry?.get("pubsub-redis")
    private val logger = LoggerFactory.getLogger("RedisPubSub")
    private val json = Json { serializersModule = context.internalSerializersModule }

    /** One shared connection for both publishing and subscribing, rebuilt after [disconnect]. */
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
                val telemetry = context.openTelemetry?.let { LettuceTelemetry.create(it) }
                val clientResources = telemetry?.let {
                    ClientResources.builder().tracing(it.newTracing()).build()
                } ?: ClientResources.create()
                RedisPubSub(name, context, RedisClient.create(clientResources, url))
            }
        }
    }

    /**
     * The hot Flux for [key]. First collector (per connection) subscribes and the last
     * unsubscribes, thanks to [Flux.share]; re-SUBSCRIBE happens automatically once the refcount
     * returns to zero. [Flux.usingWhen] makes the (un)subscribe a single subscription resource.
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
        override suspend fun collect(collector: FlowCollector<T>): Unit = otel.span("pubsub.subscribe", configure = {
            setSpanKind(SpanKind.CONSUMER)
            setAttribute("pubsub.operation", "subscribe")
            setAttribute("messaging.destination", key)
            setAttribute("messaging.system", "redis")
        }) {
            subscription(key).asFlow().collect { message ->
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
            val result = connection.reactive().publish(key, message).awaitFirst()
            span?.setAttribute("pubsub.subscribers_reached", result)
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
     * cached per-channel fluxes. Idempotent; the next use rebuilds the connection, so this does not
     * permanently disable the service. The [client] is left running because it is constructor-injected
     * and may be shared with other services.
     */
    override suspend fun disconnect() {
        lifecycleLock.withLock {
            _connection.value.close()
            channels.clear()
            _connection = lazy { client.connectPubSub() }
        }
    }
}