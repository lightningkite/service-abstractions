package com.lightningkite.services.pubsub

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Service abstraction for pub/sub messaging between application components.
 *
 * PubSub provides publish-subscribe messaging for decoupled communication within
 * or across application instances. Messages are delivered to all active subscribers
 * on a channel.
 *
 * ## Available Implementations
 *
 * - **LocalPubSub** (`local`) - In-memory pub/sub within single process (default)
 * - **DebugPubSub** (`debug`) - Prints messages to console for debugging
 * - **RedisPubSub** (`redis://`) - Redis-backed pub/sub across multiple instances (requires pubsub-redis module)
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val pubsub: PubSub.Settings = PubSub.Settings("redis://localhost:6379")
 * )
 *
 * val context = SettingContext(...)
 * val pubsub: PubSub = settings.pubsub("messaging", context)
 * ```
 *
 * ## Basic Usage
 *
 * ```kotlin
 * val pubsub: PubSub = ...
 *
 * // Get a typed channel
 * val orderChannel = pubsub.get<OrderEvent>("orders")
 *
 * // Subscribe (non-blocking, returns immediately)
 * orderChannel.collect { event ->
 *     println("Received order: ${event.orderId}")
 *     processOrder(event)
 * }
 *
 * // Publish (fire-and-forget)
 * orderChannel.emit(OrderEvent(orderId = "12345", status = "shipped"))
 * ```
 *
 * ## Multiple Subscribers
 *
 * All active subscribers receive every message:
 *
 * ```kotlin
 * val channel = pubsub.get<String>("notifications")
 *
 * // Subscriber 1
 * launch {
 *     channel.collect { msg -> logger.info("Logger: $msg") }
 * }
 *
 * // Subscriber 2
 * launch {
 *     channel.collect { msg -> sendToWebSocket(msg) }
 * }
 *
 * // Both subscribers receive this
 * channel.emit("Hello everyone!")
 * ```
 *
 * ## Channel Types
 *
 * ```kotlin
 * // Typed channel with serialization
 * val typedChannel = pubsub.get<MyData>("my-channel")
 *
 * // String channel (no serialization)
 * val stringChannel = pubsub.string("simple-channel")
 * ```
 *
 * ## Important Gotchas
 *
 * - **At-most-once delivery**: Messages are not queued - only active subscribers receive them
 * - **No persistence**: Messages are lost if no subscribers are listening
 * - **No ordering guarantees**: Message order may not be preserved across network
 * - **Fire-and-forget**: [emit] doesn't wait for subscribers or confirm delivery
 * - **Local scope**: LocalPubSub only works within single JVM process
 * - **No replay**: Subscribers only receive messages sent after they subscribe
 * - **Serialization**: Messages must be @Serializable for typed channels
 * - **Channel isolation**: Different keys are completely independent channels
 * - **No backpressure**: Fast publishers can overwhelm slow subscribers
 *
 * ## When to Use PubSub vs Other Patterns
 *
 * - **Use PubSub**: Real-time notifications, event broadcasting, cache invalidation
 * - **Use Database**: Durable messaging, transactional guarantees, ordering requirements
 * - **Use Cache**: Shared state, coordination, distributed locks
 * - **Use Queue**: Work distribution, task processing, guaranteed delivery
 *
 * @see PubSubChannel
 */
public interface PubSub : Service {
    /**
     * Configuration for instantiating a PubSub service.
     *
     * The URL scheme determines the messaging backend:
     * - `local` - In-memory pub/sub within process (default)
     * - `debug` - Console logging implementation for debugging
     * - `redis://host:port` - Redis pub/sub for cross-instance messaging
     *
     * @property url Connection string defining the pub/sub implementation
     */
    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "local"
    ) : Setting<PubSub> {
        public companion object : UrlSettingParser<PubSub>() {
            init {
                register("local") { name, _, context -> LocalPubSub(name, context) }
                register("debug") { name, _, context -> DebugPubSub(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): PubSub {
            return parse(name, url, context)
        }
    }

    /**
     * Gets a channel for a specific key with a serializer.
     */
    public fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T>

    /**
     * Gets a string channel for a specific key.
     */
    public fun string(key: String): PubSubChannel<String>

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 1.minutes

    /**
     * Checks the health of the pub-sub service by publishing a test message.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return try {
            get<Boolean>("health-check-test-key").emit(true)
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

/**
 * Gets a channel for a specific key using the default serializer.
 */
public inline operator fun <reified T : Any> PubSub.get(key: String): PubSubChannel<T> {
    return get(key, context.internalSerializersModule.serializer<T>())
}

/**
 * A bidirectional channel for publishing and subscribing to typed messages.
 *
 * PubSubChannel implements both [Flow] (for subscribing) and [FlowCollector] (for publishing),
 * providing a symmetric API for message passing.
 *
 * ## Subscribing (Flow)
 *
 * ```kotlin
 * val channel = pubsub.get<Event>("events")
 *
 * // Collect messages (suspends until cancelled)
 * channel.collect { event ->
 *     handleEvent(event)
 * }
 * ```
 *
 * ## Publishing (FlowCollector)
 *
 * ```kotlin
 * // Emit a message to all subscribers
 * channel.emit(Event("user-login", userId = "123"))
 * ```
 *
 * ## Symmetric Usage
 *
 * Because it implements both interfaces, you can wire channels together:
 *
 * ```kotlin
 * val input = pubsub1.get<String>("input")
 * val output = pubsub2.get<String>("output")
 *
 * // Forward all messages from input to output
 * input.collect { msg ->
 *     output.emit(msg.uppercase())
 * }
 * ```
 *
 * @param T Message type (must be @Serializable for typed channels)
 * @see PubSub
 */
public interface PubSubChannel<T> : Flow<T>, FlowCollector<T>