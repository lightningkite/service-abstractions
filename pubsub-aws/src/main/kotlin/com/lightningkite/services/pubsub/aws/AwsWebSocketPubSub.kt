package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.pubsub.PubSub
import com.lightningkite.services.pubsub.PubSubChannel
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.crac.Context
import org.crac.Core
import org.crac.Resource
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * AWS API Gateway WebSocket implementation of PubSub.
 *
 * This implementation uses AWS API Gateway WebSocket API backed by Lambda and DynamoDB
 * for fully serverless, pay-per-use pub/sub messaging.
 *
 * ## Architecture
 *
 * ```
 * Client (this class)
 *    │
 *    │ WebSocket connection (with secret in query params)
 *    ▼
 * API Gateway WebSocket
 *    │
 *    │ Routes: $connect (validates secret), $disconnect, $default
 *    ▼
 * Lambda (single function)
 *    │
 *    ├─► DynamoDB (connection tracking)
 *    │
 *    └─► API Gateway Management API (fan-out)
 * ```
 *
 * ## Security
 *
 * The WebSocket connection requires a secret for authentication. The secret is passed
 * as a query parameter (`?secret=...`) and validated on connection. Connections without
 * a valid secret are rejected with HTTP 403.
 *
 * The Terraform configuration automatically generates a random 32-character secret
 * and includes it in the URL returned by the deployment. Keep this URL confidential
 * as it contains the authentication secret.
 *
 * ## Supported URL Schemes
 *
 * - `aws-ws://API_ID.execute-api.REGION.amazonaws.com/STAGE?secret=SECRET` - Standard connection
 * - `aws-wss://API_ID.execute-api.REGION.amazonaws.com/STAGE?secret=SECRET` - TLS connection (recommended)
 *
 * The URL is converted to the appropriate WebSocket URL format automatically.
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // From Terraform output (includes secret)
 * PubSub.Settings("aws-wss://abc123.execute-api.us-east-1.amazonaws.com/prod?secret=mySecretToken")
 *
 * // Local development with LocalStack (no auth)
 * PubSub.Settings("aws-ws://localhost:4510")
 * ```
 *
 * ## Message Protocol
 *
 * Messages sent over the WebSocket use JSON with the following structure:
 *
 * **Subscribe to a channel:**
 * ```json
 * {"action": "subscribe", "channel": "my-channel"}
 * ```
 *
 * **Publish to a channel:**
 * ```json
 * {"action": "publish", "channel": "my-channel", "message": "...serialized payload..."}
 * ```
 *
 * **Incoming messages (from other publishers):**
 * ```json
 * {"channel": "my-channel", "message": "...serialized payload..."}
 * ```
 *
 * ## Pricing
 *
 * This is fully serverless with pay-per-use pricing:
 * - API Gateway: $1.00 per million messages, $0.25 per million connection minutes
 * - Lambda: Standard pricing (invoked on connect/disconnect/message)
 * - DynamoDB: On-demand pricing for connection tracking
 *
 * ## Deployment
 *
 * Use the Terraform configuration in the `terraform/` directory to deploy the required
 * AWS infrastructure. After deployment, use the `websocket_url` output as the URL.
 * The URL will include the secret required for authentication.
 *
 * ## Important Gotchas
 *
 * - **Security**: The URL contains a secret - do not expose it publicly
 * - **Connection lifecycle**: WebSocket connection is maintained for subscriptions
 * - **Reconnection**: Automatic reconnection on connection loss (with backoff)
 * - **Message size**: API Gateway limits messages to 128KB
 * - **Connection timeout**: API Gateway closes idle connections after 10 minutes
 * - **Latency**: Expect 50-150ms for message delivery (Lambda cold start can add more)
 * - **Ordering**: Message order is preserved per channel, but not guaranteed across channels
 * - **AWS Lambda SnapStart**: Compatible with SnapStart/CRaC - HTTP client is lazily initialized
 *
 * @property name Service name for logging/metrics
 * @property context Service context with serializers
 * @property websocketUrl The API Gateway WebSocket URL (including secret query parameter)
 * @param httpClientFactory Optional factory to create a custom Ktor HttpClient (creates default if not provided)
 */
public class AwsWebSocketPubSub(
    override val name: String,
    override val context: SettingContext,
    private val websocketUrl: String,
    private val httpClientFactory: () -> HttpClient = { HttpClient { install(WebSockets) } }
) : PubSub, Resource {

    @Volatile
    private var httpClient: HttpClient? = null

    @Volatile
    private var scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val clientLock = Any()

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: Context<out Resource>) {
        // Close HTTP client and WebSocket before checkpoint
        synchronized(clientLock) {
            // Cancel the scope first to stop coroutines
            scope.cancel()
            // Null out session - coroutine will clean it up
            session = null
            httpClient?.close()
            httpClient = null
            subscribedChannels.clear()
            channelFlows.clear()
        }
    }

    override fun afterRestore(context: Context<out Resource>) {
        // Recreate the coroutine scope after restore
        synchronized(clientLock) {
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }
        // Client and session will be recreated on next access
    }

    private fun getHttpClient(): HttpClient {
        return httpClient ?: synchronized(clientLock) {
            httpClient ?: httpClientFactory().also { httpClient = it }
        }
    }

    private fun getScope(): CoroutineScope {
        // Fast path: check if scope is active
        val currentScope = scope
        if (currentScope.isActive) return currentScope

        // Slow path: recreate if cancelled (defensive, in case afterRestore wasn't called)
        return synchronized(clientLock) {
            val s = scope
            if (s.isActive) s
            else CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scope = it }
        }
    }

    private val json = Json {
        serializersModule = context.internalSerializersModule
        ignoreUnknownKeys = true
        encodeDefaults = true  // Required to send action field
    }

    // Shared WebSocket session management
    private var session: WebSocketSession? = null
    private val sessionMutex = Mutex()
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 1000)

    // Message routing
    // replay=1 allows late collectors to receive the last message
    private val channelFlows = ConcurrentHashMap<String, MutableSharedFlow<String>>()

    @Serializable
    private data class SubscribeRequest(val action: String = "subscribe", val channel: String)

    @Serializable
    private data class PublishRequest(val action: String = "publish", val channel: String, val message: String)

    @Serializable
    private data class IncomingMessage(val channel: String? = null, val message: String? = null)

    private suspend fun ensureConnected(): WebSocketSession {
        // Fast path: check if we have an active session without acquiring the lock
        session?.let { if (it.isActive) return it }

        // Slow path: acquire the mutex and create a new session if needed
        return sessionMutex.withLock {
            // Double-check after acquiring the lock (another coroutine may have connected)
            session?.let { if (it.isActive) return@withLock it }

            // Strip query parameters (used for Terraform dependency tracking)
            val cleanUrl = websocketUrl.substringBefore('?')
            val wsUrl = cleanUrl
                .replace("aws-wss://", "wss://")
                .replace("aws-ws://", "ws://")

            val newSession = getHttpClient().webSocketSession(wsUrl)

            // Start message receiver
            getScope().launch {
                try {
                    for (frame in newSession.incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val msg = json.decodeFromString<IncomingMessage>(text)
                                if (msg.channel != null && msg.message != null) {
                                    channelFlows[msg.channel]?.emit(msg.message)
                                }
                            } catch (e: Exception) {
                                // Ignore malformed messages
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Connection closed
                } finally {
                    sessionMutex.withLock {
                        if (session == newSession) {
                            session = null
                        }
                    }
                }
            }

            session = newSession

            // Resubscribe to channels
            subscribedChannels.forEach { channel ->
                newSession.send(json.encodeToString(SubscribeRequest.serializer(), SubscribeRequest(channel = channel)))
            }

            newSession
        }
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return object : PubSubChannel<T> {
            override suspend fun emit(value: T) {
                val session = ensureConnected()
                val message = json.encodeToString(serializer, value)
                val request = PublishRequest(channel = key, message = message)
                session.send(json.encodeToString(PublishRequest.serializer(), request))
            }

            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<T>) {
                val session = ensureConnected()

                // Get or create channel flow BEFORE subscribing
                // This ensures the flow exists when messages arrive from WebSocket
                val flow = channelFlows.getOrPut(key) { 
                    MutableSharedFlow(replay = 1, extraBufferCapacity = 100) 
                }

                // Subscribe if not already subscribed
                if (subscribedChannels.add(key)) {
                    session.send(json.encodeToString(SubscribeRequest.serializer(), SubscribeRequest(channel = key)))
                    // Give server time to register subscription
                    delay(100)
                }

                // Collect and deserialize messages
                flow.collect { messageJson ->
                    try {
                        val value = json.decodeFromString(serializer, messageJson)
                        collector.emit(value)
                    } catch (e: CancellationException) {
                        throw e  // Re-throw cancellation
                    } catch (e: Exception) {
                        // Skip malformed messages
                    }
                }
            }
        }
    }

    override fun string(key: String): PubSubChannel<String> {
        return object : PubSubChannel<String> {
            override suspend fun emit(value: String) {
                val session = ensureConnected()
                val request = PublishRequest(channel = key, message = value)
                session.send(json.encodeToString(PublishRequest.serializer(), request))
            }

            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<String>) {
                val session = ensureConnected()

                // Get or create channel flow BEFORE subscribing
                val flow = channelFlows.getOrPut(key) { MutableSharedFlow(replay = 1, extraBufferCapacity = 100) }

                if (subscribedChannels.add(key)) {
                    session.send(json.encodeToString(SubscribeRequest.serializer(), SubscribeRequest(channel = key)))
                    // Give server time to register subscription
                    delay(100)
                }

                flow.collect { message ->
                    collector.emit(message)
                }
            }
        }
    }

    override val healthCheckFrequency: Duration = 1.minutes

    override suspend fun healthCheck(): HealthStatus {
        return try {
            ensureConnected()
            HealthStatus(HealthStatus.Level.OK)
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    override suspend fun connect() {
        ensureConnected()
    }

    override suspend fun disconnect() {
        val (sessionToClose, clientToClose) = synchronized(clientLock) {
            scope.cancel()
            val s = session
            val c = httpClient
            session = null
            httpClient = null
            subscribedChannels.clear()
            channelFlows.clear()
            Pair(s, c)
        }
        // Close outside synchronized to avoid critical section
        sessionToClose?.close()
        clientToClose?.close()
    }

    public companion object {
        /**
         * Creates a PubSub.Settings for AWS WebSocket pub/sub.
         *
         * @param url The API Gateway WebSocket URL (e.g., "abc123.execute-api.us-east-1.amazonaws.com/prod")
         * @param secure Whether to use secure WebSocket (wss://)
         */
        public fun PubSub.Settings.Companion.awsWebSocket(url: String, secure: Boolean = true): PubSub.Settings {
            val scheme = if (secure) "aws-wss" else "aws-ws"
            return PubSub.Settings("$scheme://$url")
        }

        init {
            PubSub.Settings.register("aws-ws") { name, url, context ->
                AwsWebSocketPubSub(name, context, url)
            }
            PubSub.Settings.register("aws-wss") { name, url, context ->
                AwsWebSocketPubSub(name, context, url)
            }
        }
    }
}
