package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.Service
import com.lightningkite.services.Setting
import com.lightningkite.services.SettingContext
import com.lightningkite.services.UrlSettingParser
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

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

    /**
     * The frequency at which health checks should be performed.
     */
    public override val healthCheckFrequency: Duration
        get() = 6.hours

    /**
     * Checks the health of the MQTT auth service.
     */
    public override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK)
    }
}

/**
 * Functional interface for your auth logic.
 * Implement this and wire it up in your HTTP handler.
 */
public fun interface MqttAuthHandler {
    public suspend fun authenticate(request: MqttAuthRequest): MqttAuthResponse
}
