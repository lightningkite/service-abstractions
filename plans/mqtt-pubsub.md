# MQTT PubSub Implementation Plan

## Overview

Add MQTT support to service-abstractions with two distinct interfaces:
1. **MqttPubSub** - Client interface for publishing/subscribing to MQTT topics
2. **MqttAuthHandler** - HTTP callback interface for broker authentication/authorization

AWS IoT Core integration uses a thin Node.js Lambda shim that forwards auth requests to your HTTP server, avoiding a separate Lambda deployment path.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Your Application Server                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐              ┌──────────────────────────────────────┐ │
│  │   MqttPubSub     │              │   MqttAuthService                    │ │
│  │   (client)       │              │   (WebhookSubservice pattern)        │ │
│  │                  │              │                                      │ │
│  │  - publish()     │              │   onAuth: WebhookSubservice          │ │
│  │  - subscribe()   │              │     .configureWebhook(httpUrl)       │ │
│  │  - get<T>(topic) │              │     .parse(headers, body) → Request  │ │
│  └────────┬─────────┘              └──────────────────┬───────────────────┘ │
│           │                                           │                      │
└───────────┼───────────────────────────────────────────┼──────────────────────┘
            │                                           │
            │ MQTT                                      │ HTTP POST
            ▼                                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              MQTT Broker                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   Option A: EMQX / Mosquitto / HiveMQ                                       │
│   - HTTP auth webhook configured via configureWebhook() API call            │
│                                                                              │
│   Option B: AWS IoT Core                                                     │
│   - Custom authorizer Lambda (thin Node.js shim)                            │
│   - configureWebhook() updates Lambda env var via AWS SDK                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
            │
            │ MQTT
            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         IoT Devices / MQTT Clients                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Key insight**: Uses `WebhookSubservice` pattern like `SmsInboundService`. The `configureWebhook(httpUrl)`
method is called at server startup with your actual endpoint URL, and the service configures the external
broker (via API call for EMQX, or Lambda env var update for AWS IoT Core).

---

## Module Structure

```
pubsub-mqtt/                           # Core abstractions (multiplatform)
├── src/commonMain/kotlin/.../mqtt/
│   ├── MqttPubSub.kt                  # Client interface extending PubSub
│   ├── MqttAuthHandler.kt             # Auth callback interface
│   ├── MqttAuthRequest.kt             # Request/response data classes
│   └── MqttAuthHttpHandler.kt         # Ktor/HTTP handler for auth endpoint
└── build.gradle.kts

pubsub-mqtt-paho/                      # Generic MQTT client via Eclipse Paho (JVM)
├── src/main/kotlin/.../mqtt/paho/
│   ├── PahoMqttPubSub.kt              # Paho client implementation
│   └── tf.kt                          # Terraform for self-hosted brokers (optional)
└── build.gradle.kts

pubsub-mqtt-aws/                       # AWS IoT Core implementation (JVM)
├── src/main/kotlin/.../mqtt/aws/
│   ├── AwsIotCorePubSub.kt            # AWS IoT SDK v2 client
│   └── tf.kt                          # Terraform for IoT Core + Lambda shim
├── src/main/resources/
│   └── aws-iot-auth-shim/             # Node.js Lambda source
│       ├── index.js                   # Thin shim that calls HTTP endpoint
│       └── package.json
└── build.gradle.kts

pubsub-mqtt-test/                      # Shared test suite
├── src/commonMain/kotlin/.../mqtt/test/
│   ├── MqttPubSubTests.kt             # Contract tests for client
│   └── MqttAuthHandlerTests.kt        # Contract tests for auth handler
└── build.gradle.kts
```

---

## Phase 1: Core Abstractions (`pubsub-mqtt`)

### 1.1 MqttPubSub Interface

```kotlin
// MqttPubSub.kt
package com.lightningkite.services.pubsub.mqtt

/**
 * MQTT-specific PubSub with topic wildcards and QoS support.
 *
 * Extends the base PubSub interface with MQTT-specific features:
 * - Topic wildcards (+ single level, # multi-level)
 * - Quality of Service levels
 * - Retained messages
 * - Last Will and Testament
 *
 * ## URL Schemes
 *
 * - `mqtt://host:1883` - Unencrypted MQTT
 * - `mqtt://user:pass@host:1883` - With credentials
 * - `mqtts://host:8883` - TLS encrypted
 * - `aws-iot://endpoint.iot.region.amazonaws.com` - AWS IoT Core
 *
 * ## Topic Wildcards
 *
 * ```kotlin
 * // Subscribe to all sensors
 * mqtt.get<SensorReading>("sensors/+/temperature").collect { ... }
 *
 * // Subscribe to everything under a device
 * mqtt.get<DeviceEvent>("devices/device-123/#").collect { ... }
 * ```
 */
public interface MqttPubSub : PubSub {

    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "mqtt://localhost:1883"
    ) : Setting<MqttPubSub> {
        public companion object : UrlSettingParser<MqttPubSub>() {
            init {
                register("local") { name, _, context -> LocalMqttPubSub(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): MqttPubSub {
            return parse(name, url, context)
        }
    }

    /**
     * Get a channel with MQTT-specific options.
     */
    public fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: QoS = QoS.AtLeastOnce,
        retained: Boolean = false
    ): MqttChannel<T>

    /**
     * MQTT Quality of Service levels.
     */
    public enum class QoS {
        /** Fire and forget - no delivery guarantee */
        AtMostOnce,
        /** Guaranteed delivery at least once (may duplicate) */
        AtLeastOnce,
        /** Guaranteed exactly once delivery */
        ExactlyOnce
    }
}

/**
 * MQTT channel with publish options.
 */
public interface MqttChannel<T> : PubSubChannel<T> {
    /** Publish with explicit retain flag */
    public suspend fun emit(value: T, retain: Boolean)
}

/** Extension for reified type */
public inline fun <reified T : Any> MqttPubSub.get(
    topic: String,
    qos: MqttPubSub.QoS = MqttPubSub.QoS.AtLeastOnce,
    retained: Boolean = false
): MqttChannel<T> = get(topic, context.internalSerializersModule.serializer(), qos, retained)
```

### 1.2 MqttAuthService Interface

Uses the `WebhookSubservice` pattern like `SmsInboundService`. The service is responsible for:
1. Configuring the external broker with your webhook URL (via `configureWebhook`)
2. Parsing incoming auth requests from the broker
3. Your app provides a handler function for the actual auth logic

```kotlin
// MqttAuthService.kt
package com.lightningkite.services.pubsub.mqtt

/**
 * Service for handling MQTT authentication/authorization callbacks from brokers.
 *
 * Uses the WebhookSubservice pattern - call [onAuth.configureWebhook] at server startup
 * to configure the broker with your endpoint URL.
 *
 * ## Available Implementations
 *
 * - **ConsoleMqttAuthService** (`console`) - Logs auth requests, always allows (dev/testing)
 * - **TestMqttAuthService** (`test`) - Collects auth requests for testing
 * - **EmqxMqttAuthService** (`emqx://`) - Configures EMQX HTTP auth backend
 * - **AwsIotMqttAuthService** (`aws-iot-auth://`) - Configures AWS IoT Core custom authorizer Lambda
 *
 * ## Configuration
 *
 * ```kotlin
 * @Serializable
 * data class ServerSettings(
 *     val mqttAuth: MqttAuthService.Settings = MqttAuthService.Settings("aws-iot-auth://my-authorizer")
 * )
 *
 * val mqttAuth = settings.mqttAuth("mqtt-auth", context)
 *
 * // At server startup, configure the broker with your endpoint
 * mqttAuth.onAuth.configureWebhook("https://yourserver.com/mqtt/auth")
 * ```
 *
 * ## Webhook Handler
 *
 * In your HTTP routing, expose the webhook endpoint:
 *
 * ```kotlin
 * routing {
 *     post("/mqtt/auth") {
 *         val request = mqttAuth.onAuth.parse(
 *             call.request.queryParameters.entries().flatMap { (k, v) -> v.map { k to it } },
 *             call.request.headers.entries().associate { it.key to it.value },
 *             TypedData(call.receiveText(), call.request.contentType()?.toString())
 *         )
 *
 *         // Your auth logic
 *         val response = myAuthHandler.authenticate(request)
 *
 *         // Render response in broker's expected format
 *         val httpResponse = mqttAuth.onAuth.render(response)
 *         call.respondBytes(httpResponse.body?.bytes ?: byteArrayOf(),
 *             status = HttpStatusCode.fromValue(httpResponse.status))
 *     }
 * }
 * ```
 */
public interface MqttAuthService : Service {

    @Serializable
    @JvmInline
    public value class Settings(
        public val url: String = "console"
    ) : Setting<MqttAuthService> {
        public companion object : UrlSettingParser<MqttAuthService>() {
            init {
                register("console") { name, _, context -> ConsoleMqttAuthService(name, context) }
                register("test") { name, _, context -> TestMqttAuthService(name, context) }
            }
        }

        override fun invoke(name: String, context: SettingContext): MqttAuthService {
            return parse(name, url, context)
        }
    }

    /**
     * Webhook subservice for receiving MQTT auth callbacks.
     *
     * - [configureWebhook]: Configure broker to call your endpoint
     * - [parse]: Parse broker's request format into [MqttAuthRequest]
     * - [render]: Render [MqttAuthResponse] in broker's expected format
     */
    public val onAuth: WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse>
}

/**
 * Functional interface for your auth logic.
 * Implement this and wire it up in your HTTP handler.
 */
public fun interface MqttAuthHandler {
    public suspend fun authenticate(request: MqttAuthRequest): MqttAuthResponse
}
```

### 1.3 Auth Request/Response Data Classes

```kotlin
// MqttAuthRequest.kt
package com.lightningkite.services.pubsub.mqtt

@Serializable
public data class MqttAuthRequest(
    /** MQTT client identifier */
    val clientId: String,
    /** Username from CONNECT packet (optional) */
    val username: String? = null,
    /** Password from CONNECT packet - often contains JWT or API key */
    val password: String? = null,
    /** Client's source IP address */
    val sourceIp: String? = null,
    /** TLS client certificate CN if using mTLS */
    val certificateCn: String? = null,
    /** Additional protocol-specific data */
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
public sealed class MqttAuthResponse {

    @Serializable
    public data class Allow(
        /** Identifier for this authenticated principal (user ID, device ID, etc.) */
        val principalId: String,
        /**
         * Topics this client can publish to.
         * Supports MQTT wildcards: + (single level), # (multi-level)
         * Use ${clientId} as placeholder for the client's ID.
         */
        val publishTopics: List<String> = emptyList(),
        /**
         * Topics this client can subscribe to.
         * Supports MQTT wildcards and ${clientId} placeholder.
         */
        val subscribeTopics: List<String> = emptyList(),
        /** Force disconnect after this many seconds (for token expiry) */
        val disconnectAfterSeconds: Int? = null,
        /**
         * Superuser flag - if true, bypasses all ACL checks.
         * Use sparingly, typically for admin/system clients only.
         */
        val superuser: Boolean = false
    ) : MqttAuthResponse()

    @Serializable
    public data object Deny : MqttAuthResponse()
}
```

### 1.4 Console/Test Implementations

```kotlin
// ConsoleMqttAuthService.kt
package com.lightningkite.services.pubsub.mqtt

/**
 * Console implementation that logs auth requests and always allows.
 * Useful for development when you want to see what's being requested.
 */
public class ConsoleMqttAuthService(
    override val name: String,
    override val context: SettingContext
) : MqttAuthService {

    override val onAuth = object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
        private var webhookUrl: String? = null

        override suspend fun configureWebhook(httpUrl: String) {
            webhookUrl = httpUrl
            println("[$name] MQTT auth webhook configured: $httpUrl")
            println("[$name] (Console mode - no actual broker configured)")
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): MqttAuthRequest {
            // Parse as generic JSON
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString(MqttAuthRequest.serializer(), body.text())
        }

        override suspend fun render(output: MqttAuthResponse): HttpAdapter.HttpResponseLike {
            val json = Json.encodeToString(MqttAuthResponse.serializer(), output)
            return HttpAdapter.HttpResponseLike(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = TypedData(json.encodeToByteArray(), "application/json")
            )
        }

        override suspend fun onSchedule() {}
    }
}

// TestMqttAuthService.kt
/**
 * Test implementation that collects auth requests for verification.
 */
public class TestMqttAuthService(
    override val name: String,
    override val context: SettingContext
) : MqttAuthService {
    public val authRequests: MutableList<MqttAuthRequest> = mutableListOf()
    public var nextResponse: MqttAuthResponse = MqttAuthResponse.Deny

    override val onAuth = object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
        override suspend fun configureWebhook(httpUrl: String) {
            // No-op for tests
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): MqttAuthRequest {
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString(MqttAuthRequest.serializer(), body.text())
        }

        override suspend fun render(output: MqttAuthResponse): HttpAdapter.HttpResponseLike {
            val json = Json.encodeToString(MqttAuthResponse.serializer(), output)
            return HttpAdapter.HttpResponseLike(200, mapOf(), TypedData(json.encodeToByteArray(), "application/json"))
        }

        override suspend fun onSchedule() {}
    }
}
```

### 1.5 Local/Test Implementation

```kotlin
// LocalMqttPubSub.kt
package com.lightningkite.services.pubsub.mqtt

/**
 * In-memory MQTT PubSub for testing.
 * Simulates MQTT topic wildcards but runs entirely in-process.
 */
public class LocalMqttPubSub(
    override val name: String,
    override val context: SettingContext
) : MqttPubSub {
    private val channels = ConcurrentHashMap<String, MutableSharedFlow<Pair<String, String>>>()
    private val masterFlow = MutableSharedFlow<Pair<String, String>>()

    override fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: MqttPubSub.QoS,
        retained: Boolean
    ): MqttChannel<T> {
        return object : MqttChannel<T> {
            override suspend fun emit(value: T) = emit(value, retained)

            override suspend fun emit(value: T, retain: Boolean) {
                val json = Json.encodeToString(serializer, value)
                masterFlow.emit(topic to json)
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                masterFlow
                    .filter { (publishedTopic, _) -> topicMatches(topic, publishedTopic) }
                    .map { (_, json) -> Json.decodeFromString(serializer, json) }
                    .collect(collector)
            }
        }
    }

    // MQTT topic matching: + matches one level, # matches remaining levels
    private fun topicMatches(pattern: String, topic: String): Boolean {
        val patternParts = pattern.split("/")
        val topicParts = topic.split("/")

        var pi = 0
        var ti = 0

        while (pi < patternParts.size && ti < topicParts.size) {
            when (patternParts[pi]) {
                "#" -> return true  // # matches everything remaining
                "+" -> { pi++; ti++ }  // + matches exactly one level
                topicParts[ti] -> { pi++; ti++ }
                else -> return false
            }
        }

        return pi == patternParts.size && ti == topicParts.size
    }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> {
        return get(key, serializer, MqttPubSub.QoS.AtLeastOnce, false)
    }

    override fun string(key: String): PubSubChannel<String> {
        return get(key, String.serializer())
    }
}
```

---

## Phase 2: Paho Client Implementation (`pubsub-mqtt-paho`)

### 2.1 Paho MQTT Client

```kotlin
// PahoMqttPubSub.kt
package com.lightningkite.services.pubsub.mqtt.paho

/**
 * MQTT client implementation using Eclipse Paho.
 *
 * Works with any standard MQTT broker: EMQX, Mosquitto, HiveMQ, etc.
 *
 * ## Connection URLs
 *
 * - `mqtt://host:1883` - TCP connection
 * - `mqtt://user:pass@host:1883` - With credentials
 * - `mqtts://host:8883` - TLS connection
 * - `mqtt://host:1883?clientId=my-client` - Custom client ID
 *
 * ## Dependencies
 *
 * Uses Eclipse Paho MQTT v5 client for MQTT 5.0 support.
 */
public class PahoMqttPubSub(
    override val name: String,
    override val context: SettingContext,
    private val client: MqttAsyncClient,
    private val defaultQos: Int = 1
) : MqttPubSub {

    private val json = Json { serializersModule = context.internalSerializersModule }
    private val tracer: Tracer? = context.openTelemetry?.getTracer("pubsub-mqtt-paho")
    private val subscriptions = ConcurrentHashMap<String, MutableSharedFlow<MqttMessage>>()

    public companion object {
        init {
            MqttPubSub.Settings.register("mqtt") { name, url, context ->
                createFromUrl(name, url, context, useTls = false)
            }
            MqttPubSub.Settings.register("mqtts") { name, url, context ->
                createFromUrl(name, url, context, useTls = true)
            }
        }

        private fun createFromUrl(
            name: String,
            url: String,
            context: SettingContext,
            useTls: Boolean
        ): PahoMqttPubSub {
            val parsed = URI(url)
            val clientId = parsed.query?.let {
                Regex("clientId=([^&]+)").find(it)?.groupValues?.get(1)
            } ?: "service-$name-${UUID.randomUUID().toString().take(8)}"

            val brokerUrl = if (useTls) {
                "ssl://${parsed.host}:${parsed.port.takeIf { it > 0 } ?: 8883}"
            } else {
                "tcp://${parsed.host}:${parsed.port.takeIf { it > 0 } ?: 1883}"
            }

            val client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

            val options = MqttConnectionOptions().apply {
                isCleanStart = true
                isAutomaticReconnect = true
                connectionTimeout = 30
                keepAliveInterval = 60

                // Extract credentials from URL
                parsed.userInfo?.split(":")?.let { parts ->
                    userName = parts[0]
                    if (parts.size > 1) password = parts[1].toCharArray()
                }
            }

            return PahoMqttPubSub(name, context, client, defaultQos = 1).also {
                // Connect lazily on first use
            }
        }
    }

    private suspend fun ensureConnected() {
        if (!client.isConnected) {
            suspendCancellableCoroutine { cont ->
                client.connect(options, null, object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken) { cont.resume(Unit) }
                    override fun onFailure(token: IMqttToken, ex: Throwable) { cont.resumeWithException(ex) }
                })
            }
        }
    }

    override fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: MqttPubSub.QoS,
        retained: Boolean
    ): MqttChannel<T> {
        val mqttQos = when (qos) {
            MqttPubSub.QoS.AtMostOnce -> 0
            MqttPubSub.QoS.AtLeastOnce -> 1
            MqttPubSub.QoS.ExactlyOnce -> 2
        }

        return object : MqttChannel<T> {
            override suspend fun emit(value: T) = emit(value, retained)

            override suspend fun emit(value: T, retain: Boolean) {
                ensureConnected()
                val payload = json.encodeToString(serializer, value).toByteArray()
                val message = MqttMessage(payload).apply {
                    this.qos = mqttQos
                    this.isRetained = retain
                }

                suspendCancellableCoroutine { cont ->
                    client.publish(topic, message, null, object : IMqttActionListener {
                        override fun onSuccess(token: IMqttToken) { cont.resume(Unit) }
                        override fun onFailure(token: IMqttToken, ex: Throwable) { cont.resumeWithException(ex) }
                    })
                }
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                ensureConnected()

                val flow = subscriptions.getOrPut(topic) {
                    MutableSharedFlow<MqttMessage>().also { flow ->
                        client.subscribe(topic, mqttQos) { _, msg ->
                            runBlocking { flow.emit(msg) }
                        }
                    }
                }

                flow.map { msg ->
                    json.decodeFromString(serializer, msg.payload.decodeToString())
                }.collect(collector)
            }
        }
    }

    override suspend fun connect() {
        ensureConnected()
    }

    override suspend fun disconnect() {
        if (client.isConnected) {
            suspendCancellableCoroutine { cont ->
                client.disconnect(null, object : IMqttActionListener {
                    override fun onSuccess(token: IMqttToken) { cont.resume(Unit) }
                    override fun onFailure(token: IMqttToken, ex: Throwable) { cont.resume(Unit) }
                })
            }
        }
    }

    // ... remaining PubSub interface methods
}
```

### 2.2 Build Configuration

```kotlin
// pubsub-mqtt-paho/build.gradle.kts
plugins {
    id("lk-jvm")
    id("lk-publish")
}

dependencies {
    api(project(":pubsub-mqtt"))

    // Eclipse Paho MQTT v5 client
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    // For connection options
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation(project(":pubsub-mqtt-test"))
}
```

---

## Phase 3: AWS IoT Core Implementation (`pubsub-mqtt-aws`)

This module provides both the MQTT client (for publishing/subscribing) and the auth service (for receiving auth callbacks from the IoT Core custom authorizer).

### 3.1 AWS IoT Auth Service

The key innovation: `configureWebhook()` updates the Lambda's `AUTH_ENDPOINT` environment variable at runtime via AWS SDK.

```kotlin
// AwsIotMqttAuthService.kt
package com.lightningkite.services.pubsub.mqtt.aws

/**
 * AWS IoT Core auth service that configures a custom authorizer Lambda.
 *
 * When [configureWebhook] is called, this service updates the Lambda function's
 * AUTH_ENDPOINT environment variable to point to your HTTP endpoint.
 *
 * ## URL Format
 *
 * `aws-iot-auth://[lambda-function-name]?region=[region]`
 *
 * ## Example
 *
 * ```kotlin
 * // In settings
 * val mqttAuth = MqttAuthService.Settings("aws-iot-auth://my-iot-authorizer?region=us-east-1")
 *
 * // At startup
 * mqttAuth.onAuth.configureWebhook("https://api.myserver.com/mqtt/auth")
 * // This calls AWS Lambda UpdateFunctionConfiguration to set AUTH_ENDPOINT env var
 * ```
 */
public class AwsIotMqttAuthService(
    override val name: String,
    override val context: SettingContext,
    private val lambdaFunctionName: String,
    private val region: String
) : MqttAuthService {

    private val lambdaClient = LambdaClient.builder()
        .region(Region.of(region))
        .build()

    private val tracer: Tracer? = context.openTelemetry?.getTracer("mqtt-auth-aws-iot")

    public companion object {
        init {
            MqttAuthService.Settings.register("aws-iot-auth") { name, url, context ->
                // Parse: aws-iot-auth://lambda-function-name?region=us-east-1
                val parsed = URI(url)
                val functionName = parsed.host
                val params = parsed.query?.split("&")?.associate {
                    val (k, v) = it.split("=", limit = 2)
                    k to v
                } ?: emptyMap()
                val region = params["region"] ?: "us-east-1"

                AwsIotMqttAuthService(name, context, functionName, region)
            }
        }
    }

    override val onAuth = object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
        private var webhookUrl: String? = null

        override suspend fun configureWebhook(httpUrl: String) {
            val span = tracer?.spanBuilder("mqtt.auth.configure_webhook")
                ?.setSpanKind(SpanKind.CLIENT)
                ?.setAttribute("mqtt.auth.provider", "aws-iot-core")
                ?.setAttribute("mqtt.auth.lambda", lambdaFunctionName)
                ?.setAttribute("webhook.url", httpUrl)
                ?.startSpan()

            try {
                webhookUrl = httpUrl

                // Get current Lambda configuration
                val getConfigRequest = GetFunctionConfigurationRequest.builder()
                    .functionName(lambdaFunctionName)
                    .build()

                val currentConfig = lambdaClient.getFunctionConfiguration(getConfigRequest)
                val currentEnv = currentConfig.environment()?.variables() ?: emptyMap()

                // Update AUTH_ENDPOINT env var
                val newEnv = currentEnv.toMutableMap()
                newEnv["AUTH_ENDPOINT"] = httpUrl

                val updateRequest = UpdateFunctionConfigurationRequest.builder()
                    .functionName(lambdaFunctionName)
                    .environment(Environment.builder().variables(newEnv).build())
                    .build()

                lambdaClient.updateFunctionConfiguration(updateRequest)

                span?.setStatus(StatusCode.OK)
                logger.info { "[$name] Updated Lambda $lambdaFunctionName AUTH_ENDPOINT to $httpUrl" }

            } catch (e: Exception) {
                span?.setStatus(StatusCode.ERROR, "Failed to configure webhook: ${e.message}")
                span?.recordException(e)
                throw e
            } finally {
                span?.end()
            }
        }

        override suspend fun parse(
            queryParameters: List<Pair<String, String>>,
            headers: Map<String, List<String>>,
            body: TypedData
        ): MqttAuthRequest {
            // The Node.js shim forwards in our standard format
            val json = Json { ignoreUnknownKeys = true }
            return json.decodeFromString(MqttAuthRequest.serializer(), body.text())
        }

        override suspend fun render(output: MqttAuthResponse): HttpAdapter.HttpResponseLike {
            // Return in format the Node.js shim expects
            val json = Json.encodeToString(MqttAuthResponse.serializer(), output)
            return HttpAdapter.HttpResponseLike(
                status = 200,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = TypedData(json.encodeToByteArray(), "application/json")
            )
        }

        override suspend fun onSchedule() {
            // Could verify Lambda is still configured correctly
        }
    }

    override suspend fun disconnect() {
        lambdaClient.close()
    }
}
```

### 3.2 AWS IoT Core Client

```kotlin
// AwsIotCorePubSub.kt
package com.lightningkite.services.pubsub.mqtt.aws

/**
 * AWS IoT Core MQTT client using AWS IoT Device SDK v2.
 *
 * ## URL Format
 *
 * `aws-iot://[endpoint].iot.[region].amazonaws.com`
 *
 * ## Authentication Options
 *
 * 1. **IAM Credentials** (default for server apps):
 *    Uses default AWS credential chain (env vars, instance profile, etc.)
 *    `aws-iot://endpoint.iot.us-east-1.amazonaws.com`
 *
 * 2. **Custom Authorizer**:
 *    `aws-iot://endpoint.iot.us-east-1.amazonaws.com?authorizer=MyAuth&token=xxx`
 *
 * 3. **X.509 Certificate**:
 *    `aws-iot://endpoint.iot.us-east-1.amazonaws.com?cert=/path/cert.pem&key=/path/key.pem`
 */
public class AwsIotCorePubSub(
    override val name: String,
    override val context: SettingContext,
    private val connection: MqttClientConnection
) : MqttPubSub {

    private val json = Json { serializersModule = context.internalSerializersModule }
    private val subscriptions = ConcurrentHashMap<String, MutableSharedFlow<MqttMessage>>()

    public companion object {
        init {
            MqttPubSub.Settings.register("aws-iot") { name, url, context ->
                createFromUrl(name, url, context)
            }
        }

        private fun createFromUrl(
            name: String,
            url: String,
            context: SettingContext
        ): AwsIotCorePubSub {
            val parsed = URI(url)
            val endpoint = parsed.host
            val params = parsed.query?.split("&")?.associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            } ?: emptyMap()

            val clientId = params["clientId"]
                ?: "service-$name-${UUID.randomUUID().toString().take(8)}"

            // Build connection based on auth method
            val connection = when {
                params.containsKey("authorizer") -> {
                    // Custom authorizer with token in password field
                    val builder = AwsIotMqttConnectionBuilder.newMtlsBuilder(
                        endpoint
                    )
                    // Configure custom auth...
                    builder.build()
                }
                params.containsKey("cert") -> {
                    // X.509 certificate auth
                    AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(
                        params["cert"]!!,
                        params["key"]!!
                    ).withEndpoint(endpoint)
                     .withClientId(clientId)
                     .build()
                }
                else -> {
                    // Default: WebSocket with IAM SigV4
                    AwsIotMqttConnectionBuilder.newWebsocketBuilder()
                        .withEndpoint(endpoint)
                        .withClientId(clientId)
                        .withCredentialsProvider(DefaultCredentialsProvider.create())
                        .build()
                }
            }

            return AwsIotCorePubSub(name, context, connection)
        }
    }

    override fun <T> get(
        topic: String,
        serializer: KSerializer<T>,
        qos: MqttPubSub.QoS,
        retained: Boolean
    ): MqttChannel<T> {
        val awsQos = when (qos) {
            MqttPubSub.QoS.AtMostOnce -> QualityOfService.AT_MOST_ONCE
            MqttPubSub.QoS.AtLeastOnce -> QualityOfService.AT_LEAST_ONCE
            MqttPubSub.QoS.ExactlyOnce -> QualityOfService.AT_LEAST_ONCE // IoT Core doesn't support QoS 2
        }

        return object : MqttChannel<T> {
            override suspend fun emit(value: T) = emit(value, retained)

            override suspend fun emit(value: T, retain: Boolean) {
                val payload = json.encodeToString(serializer, value).toByteArray()
                connection.publish(
                    MqttMessage(topic, payload, awsQos, retain)
                ).get()
            }

            override suspend fun collect(collector: FlowCollector<T>) {
                val flow = subscriptions.getOrPut(topic) {
                    MutableSharedFlow<MqttMessage>().also { flow ->
                        connection.subscribe(topic, awsQos) { message ->
                            runBlocking { flow.emit(message) }
                        }.get()
                    }
                }

                flow.map { msg ->
                    json.decodeFromString(serializer, msg.payload.decodeToString())
                }.collect(collector)
            }
        }
    }

    override suspend fun connect() {
        connection.connect().get()
    }

    override suspend fun disconnect() {
        connection.disconnect().get()
    }

    // ... remaining interface methods
}
```

### 3.2 Node.js Lambda Shim

This is a thin Lambda that forwards auth requests to your HTTP endpoint.

```javascript
// src/main/resources/aws-iot-auth-shim/index.js

/**
 * AWS IoT Core Custom Authorizer Lambda
 *
 * This is a thin shim that forwards authentication requests to your
 * application server's HTTP endpoint. This keeps all auth logic in
 * your main application rather than in separate Lambda code.
 *
 * Environment Variables:
 * - AUTH_ENDPOINT: Full URL to your /mqtt/auth endpoint
 * - AUTH_TIMEOUT_MS: Timeout for HTTP call (default: 4000, must be < 5000)
 */

const https = require('https');
const http = require('http');
const url = require('url');

exports.handler = async (event) => {
    console.log('Received event:', JSON.stringify(event, null, 2));

    const authEndpoint = process.env.AUTH_ENDPOINT;
    if (!authEndpoint) {
        console.error('AUTH_ENDPOINT environment variable not set');
        return buildDenyResponse();
    }

    const timeoutMs = parseInt(process.env.AUTH_TIMEOUT_MS || '4000', 10);

    // Extract MQTT connection info from event
    const mqttContext = event.protocolData?.mqtt || {};
    const tlsContext = event.protocolData?.tls || {};

    // Build request to your auth endpoint
    const authRequest = {
        clientId: mqttContext.clientId || event.clientId || 'unknown',
        username: mqttContext.username || null,
        // Password is base64 encoded in IoT Core events
        password: mqttContext.password
            ? Buffer.from(mqttContext.password, 'base64').toString('utf8')
            : null,
        sourceIp: event.connectionMetadata?.id || null,
        certificateCn: tlsContext.serverName || null,
        metadata: {
            protocols: (event.protocols || []).join(','),
            signatureVerified: String(event.signatureVerified || false),
            awsAccountId: event.awsAccountId || ''
        }
    };

    try {
        const response = await callAuthEndpoint(authEndpoint, authRequest, timeoutMs);
        console.log('Auth response:', JSON.stringify(response, null, 2));

        if (response.type === 'Deny' || response === 'Deny') {
            return buildDenyResponse();
        }

        // Map our response format to AWS IoT policy format
        return buildAllowResponse(response, mqttContext.clientId);

    } catch (error) {
        console.error('Auth endpoint error:', error.message);
        return buildDenyResponse();
    }
};

async function callAuthEndpoint(endpoint, body, timeoutMs) {
    return new Promise((resolve, reject) => {
        const parsedUrl = url.parse(endpoint);
        const lib = parsedUrl.protocol === 'https:' ? https : http;

        const options = {
            hostname: parsedUrl.hostname,
            port: parsedUrl.port || (parsedUrl.protocol === 'https:' ? 443 : 80),
            path: parsedUrl.path,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Forwarded-For-Source': 'aws-iot-authorizer'
            },
            timeout: timeoutMs
        };

        const req = lib.request(options, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                if (res.statusCode === 200) {
                    try {
                        resolve(JSON.parse(data));
                    } catch (e) {
                        reject(new Error(`Invalid JSON response: ${data}`));
                    }
                } else {
                    reject(new Error(`HTTP ${res.statusCode}: ${data}`));
                }
            });
        });

        req.on('error', reject);
        req.on('timeout', () => {
            req.destroy();
            reject(new Error('Request timeout'));
        });

        req.write(JSON.stringify(body));
        req.end();
    });
}

function buildAllowResponse(authResponse, clientId) {
    const principalId = authResponse.principalId || clientId || 'unknown';
    const statements = [];

    // Connect permission (always needed)
    statements.push({
        Effect: 'Allow',
        Action: 'iot:Connect',
        Resource: `arn:aws:iot:*:*:client/${clientId}`
    });

    // Publish permissions
    const publishTopics = authResponse.publishTopics || [];
    if (publishTopics.length > 0) {
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Publish',
            Resource: publishTopics.map(topic =>
                `arn:aws:iot:*:*:topic/${replaceClientId(topic, clientId)}`
            )
        });
    }

    // Subscribe permissions
    const subscribeTopics = authResponse.subscribeTopics || [];
    if (subscribeTopics.length > 0) {
        // Subscribe action
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Subscribe',
            Resource: subscribeTopics.map(topic =>
                `arn:aws:iot:*:*:topicfilter/${replaceClientId(topic, clientId)}`
            )
        });

        // Receive action (needed to actually get messages)
        statements.push({
            Effect: 'Allow',
            Action: 'iot:Receive',
            Resource: subscribeTopics.map(topic =>
                `arn:aws:iot:*:*:topic/${replaceClientId(topic, clientId)}`
            )
        });
    }

    const policyDocument = {
        Version: '2012-10-17',
        Statement: statements
    };

    return {
        isAuthenticated: true,
        principalId: principalId,
        disconnectAfterInSeconds: authResponse.disconnectAfterSeconds || 86400,
        refreshAfterInSeconds: 300,
        policyDocuments: [JSON.stringify(policyDocument)]
    };
}

function buildDenyResponse() {
    return {
        isAuthenticated: false,
        principalId: 'denied',
        disconnectAfterInSeconds: 0,
        refreshAfterInSeconds: 0,
        policyDocuments: []
    };
}

// Replace ${clientId} placeholder with actual client ID
function replaceClientId(topic, clientId) {
    return topic.replace(/\$\{clientId\}/g, clientId);
}
```

```json
// src/main/resources/aws-iot-auth-shim/package.json
{
  "name": "aws-iot-auth-shim",
  "version": "1.0.0",
  "description": "Thin Lambda shim for AWS IoT Core custom authorizer",
  "main": "index.js",
  "engines": {
    "node": ">=18.0.0"
  }
}
```

### 3.3 Terraform Configuration

```kotlin
// tf.kt
package com.lightningkite.services.pubsub.mqtt.aws

/**
 * Terraform configuration for AWS IoT Core MQTT with custom authorizer.
 */
public object AwsIotCoreTerraform {

    /**
     * Generate Terraform for AWS IoT Core custom authorizer setup.
     *
     * @param name Unique name for resources
     * @param authEndpoint HTTP endpoint for auth callbacks (your server's /mqtt/auth)
     * @param lambdaMemory Memory for Lambda (default 128MB is usually enough)
     */
    public fun Terraform.awsIotMqttAuth(
        name: String,
        authEndpoint: TerraformExpression,  // e.g., "${aws_api_gateway_stage.main.invoke_url}/mqtt/auth"
        lambdaMemory: Int = 128
    ) {
        // IAM role for Lambda
        resource("aws_iam_role", "${name}_lambda_role") {
            argument("name", "${name}-iot-auth-lambda")
            argument("assume_role_policy", """
                {
                    "Version": "2012-10-17",
                    "Statement": [{
                        "Action": "sts:AssumeRole",
                        "Principal": {"Service": "lambda.amazonaws.com"},
                        "Effect": "Allow"
                    }]
                }
            """.trimIndent())
        }

        resource("aws_iam_role_policy_attachment", "${name}_lambda_basic") {
            argument("role", reference("aws_iam_role", "${name}_lambda_role", "name"))
            argument("policy_arn", "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")
        }

        // Lambda function (zip the shim code)
        resource("data.archive_file", "${name}_lambda_zip") {
            argument("type", "zip")
            argument("source_dir", "\${path.module}/aws-iot-auth-shim")
            argument("output_path", "\${path.module}/.terraform/${name}-auth-shim.zip")
        }

        resource("aws_lambda_function", "${name}_authorizer") {
            argument("function_name", "${name}-iot-authorizer")
            argument("role", reference("aws_iam_role", "${name}_lambda_role", "arn"))
            argument("handler", "index.handler")
            argument("runtime", "nodejs20.x")
            argument("memory_size", lambdaMemory)
            argument("timeout", 5)  // IoT Core max is 5 seconds
            argument("filename", reference("data.archive_file", "${name}_lambda_zip", "output_path"))
            argument("source_code_hash", reference("data.archive_file", "${name}_lambda_zip", "output_base64sha256"))

            argument("environment") {
                argument("variables") {
                    argument("AUTH_ENDPOINT", authEndpoint)
                    argument("AUTH_TIMEOUT_MS", "4000")
                }
            }
        }

        // Permission for IoT Core to invoke Lambda
        resource("aws_lambda_permission", "${name}_iot_invoke") {
            argument("statement_id", "AllowIoTCoreInvoke")
            argument("action", "lambda:InvokeFunction")
            argument("function_name", reference("aws_lambda_function", "${name}_authorizer", "function_name"))
            argument("principal", "iot.amazonaws.com")
            argument("source_arn", reference("aws_iot_authorizer", name, "arn"))
        }

        // IoT Core custom authorizer
        resource("aws_iot_authorizer", name) {
            argument("name", name)
            argument("authorizer_function_arn", reference("aws_lambda_function", "${name}_authorizer", "arn"))
            argument("signing_disabled", true)  // We validate JWT/tokens in our auth handler
            argument("status", "ACTIVE")
            argument("enable_caching_for_http", false)  // Let our server handle caching
        }

        // Output the IoT endpoint
        output("${name}_iot_endpoint") {
            value = "\${data.aws_iot_endpoint.current.endpoint_address}"
        }

        output("${name}_authorizer_name") {
            value = reference("aws_iot_authorizer", name, "name")
        }
    }

    /**
     * Data source to get the IoT endpoint for this account/region.
     */
    public fun Terraform.awsIotEndpoint() {
        resource("data.aws_iot_endpoint", "current") {
            argument("endpoint_type", "iot:Data-ATS")
        }
    }
}
```

### 3.4 Build Configuration

```kotlin
// pubsub-mqtt-aws/build.gradle.kts
plugins {
    id("lk-jvm")
    id("lk-publish")
}

dependencies {
    api(project(":pubsub-mqtt"))

    // AWS IoT Device SDK v2
    implementation("software.amazon.awssdk.iotdevicesdk:aws-iot-device-sdk:1.20.0")

    // AWS SDK for credential providers
    implementation("software.amazon.awssdk:auth:2.25.0")

    testImplementation(project(":pubsub-mqtt-test"))
}

// Copy Node.js shim to output for Terraform
tasks.register<Copy>("copyAuthShim") {
    from("src/main/resources/aws-iot-auth-shim")
    into("$buildDir/terraform/aws-iot-auth-shim")
}

tasks.named("processResources") {
    dependsOn("copyAuthShim")
}
```

---

## Phase 4: Test Suite (`pubsub-mqtt-test`)

### 4.1 Contract Tests

```kotlin
// MqttPubSubTests.kt
package com.lightningkite.services.pubsub.mqtt.test

abstract class MqttPubSubTests {
    abstract val mqtt: MqttPubSub

    @Test
    fun `basic publish subscribe`() = runTest {
        val channel = mqtt.get<TestMessage>("test/basic")
        val received = mutableListOf<TestMessage>()

        val job = launch {
            channel.take(3).collect { received.add(it) }
        }

        delay(100) // Let subscription establish

        channel.emit(TestMessage("one"))
        channel.emit(TestMessage("two"))
        channel.emit(TestMessage("three"))

        job.join()

        assertEquals(3, received.size)
        assertEquals(listOf("one", "two", "three"), received.map { it.content })
    }

    @Test
    fun `wildcard subscription single level`() = runTest {
        val channel = mqtt.get<String>("sensors/+/temperature")
        val received = mutableListOf<String>()

        val job = launch {
            channel.take(2).collect { received.add(it) }
        }

        delay(100)

        // These should match
        mqtt.get<String>("sensors/device1/temperature").emit("20.5")
        mqtt.get<String>("sensors/device2/temperature").emit("21.3")

        // This should NOT match (wrong level)
        mqtt.get<String>("sensors/device1/humidity").emit("50")

        job.join()

        assertEquals(2, received.size)
    }

    @Test
    fun `wildcard subscription multi level`() = runTest {
        val channel = mqtt.get<String>("devices/hub1/#")
        val received = mutableListOf<String>()

        val job = launch {
            channel.take(3).collect { received.add(it) }
        }

        delay(100)

        mqtt.get<String>("devices/hub1/status").emit("online")
        mqtt.get<String>("devices/hub1/sensors/temp").emit("25")
        mqtt.get<String>("devices/hub1/sensors/humidity").emit("60")

        // Should NOT match
        mqtt.get<String>("devices/hub2/status").emit("offline")

        job.join()

        assertEquals(3, received.size)
    }

    @Test
    fun `qos levels respected`() = runTest {
        // Test QoS 0 (fire and forget)
        val qos0Channel = mqtt.get<String>("test/qos0", qos = MqttPubSub.QoS.AtMostOnce)
        qos0Channel.emit("message")

        // Test QoS 1 (at least once)
        val qos1Channel = mqtt.get<String>("test/qos1", qos = MqttPubSub.QoS.AtLeastOnce)
        qos1Channel.emit("message")

        // Test QoS 2 (exactly once) - may fall back to QoS 1 on some brokers
        val qos2Channel = mqtt.get<String>("test/qos2", qos = MqttPubSub.QoS.ExactlyOnce)
        qos2Channel.emit("message")
    }

    @Serializable
    data class TestMessage(val content: String)
}

// MqttAuthHandlerTests.kt
abstract class MqttAuthHandlerTests {
    abstract val handler: MqttAuthHandler

    @Test
    fun `valid credentials return allow`() = runTest {
        val request = MqttAuthRequest(
            clientId = "device-123",
            username = "testuser",
            password = "valid-token"
        )

        val response = handler.authenticate(request)

        assertTrue(response is MqttAuthResponse.Allow)
    }

    @Test
    fun `invalid credentials return deny`() = runTest {
        val request = MqttAuthRequest(
            clientId = "device-123",
            username = "testuser",
            password = "invalid-token"
        )

        val response = handler.authenticate(request)

        assertTrue(response is MqttAuthResponse.Deny)
    }

    @Test
    fun `allowed topics include clientId substitution`() = runTest {
        val request = MqttAuthRequest(
            clientId = "device-456",
            password = "valid-token"
        )

        val response = handler.authenticate(request) as MqttAuthResponse.Allow

        // Check that ${clientId} was replaced
        assertTrue(response.publishTopics.none { it.contains("\${clientId}") })
        assertTrue(response.subscribeTopics.none { it.contains("\${clientId}") })
    }
}
```

---

## Implementation Order

1. **Phase 1: Core abstractions** (`pubsub-mqtt`)
   - `MqttPubSub` interface
   - `MqttAuthHandler` interface
   - `MqttAuthRequest`/`MqttAuthResponse` data classes
   - `LocalMqttPubSub` for testing
   - HTTP handler for auth endpoint

2. **Phase 2: Paho client** (`pubsub-mqtt-paho`)
   - `PahoMqttPubSub` implementation
   - URL parsing for mqtt:// and mqtts://
   - Connection management

3. **Phase 3: AWS IoT Core** (`pubsub-mqtt-aws`)
   - `AwsIotCorePubSub` client
   - Node.js Lambda shim (index.js)
   - Terraform definitions
   - Integration tests

4. **Phase 4: Test suite** (`pubsub-mqtt-test`)
   - Contract tests for both interfaces
   - Each implementation module runs these tests

---

## Usage Example

```kotlin
// Application settings
@Serializable
data class AppSettings(
    val database: Database.Settings,
    // Client for publishing/subscribing
    val mqtt: MqttPubSub.Settings = MqttPubSub.Settings("aws-iot://xxx.iot.us-east-1.amazonaws.com"),
    // Auth service for configuring broker callbacks
    val mqttAuth: MqttAuthService.Settings = MqttAuthService.Settings("aws-iot-auth://my-iot-authorizer?region=us-east-1")
)

// Your auth logic (functional interface)
class MyMqttAuth(
    private val database: Database,
    private val jwt: JwtService
) : MqttAuthHandler {
    override suspend fun authenticate(request: MqttAuthRequest): MqttAuthResponse {
        val token = request.password ?: return MqttAuthResponse.Deny
        val claims = jwt.verify(token) ?: return MqttAuthResponse.Deny

        val user = database.users.findOne { it._id eq claims.subject }
            ?: return MqttAuthResponse.Deny

        return MqttAuthResponse.Allow(
            principalId = user._id,
            publishTopics = listOf("users/${user._id}/+"),
            subscribeTopics = listOf("users/${user._id}/#") +
                user.subscribedChannels.map { "channels/$it/#" }
        )
    }
}

// Server setup
fun Application.module() {
    val context = SettingContext(...)
    val settings = loadSettings<AppSettings>()

    // Initialize services
    val mqtt = settings.mqtt("mqtt", context)
    val mqttAuth = settings.mqttAuth("mqtt-auth", context)

    // Your auth logic
    val myAuthHandler = MyMqttAuth(database, jwt)

    // Configure the broker to call our endpoint
    // For AWS IoT Core, this updates the Lambda's AUTH_ENDPOINT env var
    runBlocking {
        mqttAuth.onAuth.configureWebhook("https://api.yourserver.com/mqtt/auth")
    }

    routing {
        // Expose auth endpoint for MQTT broker callbacks
        post("/mqtt/auth") {
            // Parse broker's request format into our MqttAuthRequest
            val request = mqttAuth.onAuth.parse(
                queryParameters = call.request.queryParameters.entries()
                    .flatMap { (k, v) -> v.map { k to it } },
                headers = call.request.headers.entries()
                    .associate { it.key to it.value },
                body = TypedData(call.receiveText(), call.request.contentType()?.toString())
            )

            // Your auth logic
            val response = myAuthHandler.authenticate(request)

            // Render response in broker's expected format
            val httpResponse = mqttAuth.onAuth.render(response)
            call.respondBytes(
                bytes = httpResponse.body?.bytes ?: byteArrayOf(),
                status = HttpStatusCode.fromValue(httpResponse.status),
                contentType = ContentType.Application.Json
            )
        }

        // Use MQTT client in your handlers
        webSocket("/live") {
            mqtt.get<Notification>("notifications/${userId}").collect {
                send(Json.encodeToString(it))
            }
        }
    }
}
```

## Flow Summary

1. **At deploy time**: Terraform creates the AWS IoT Core custom authorizer Lambda with the Node.js shim
2. **At server startup**: `mqttAuth.onAuth.configureWebhook(url)` updates the Lambda's `AUTH_ENDPOINT` env var
3. **When device connects**: IoT Core → Lambda → HTTP POST to your server → your auth logic → response
4. **Your server publishes/subscribes**: Uses `MqttPubSub` client to talk to IoT Core
