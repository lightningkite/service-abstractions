package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import kotlinx.serialization.json.Json

/**
 * Console implementation that logs auth requests and always allows.
 * Useful for development when you want to see what's being requested.
 */
public class ConsoleMqttAuthService(
    override val name: String,
    override val context: SettingContext
) : MqttAuthService {

    override val onAuth: WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> =
        object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
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
                val request = json.decodeFromString(MqttAuthRequest.serializer(), body.text())
                println("[$name] MQTT auth request: clientId=${request.clientId}, username=${request.username}")
                return request
            }

            override suspend fun render(output: MqttAuthResponse): HttpAdapter.HttpResponseLike {
                val json = Json.encodeToString(MqttAuthResponse.serializer(), output)
                println("[$name] MQTT auth response: $output")
                return HttpAdapter.HttpResponseLike(
                    status = 200,
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = TypedData(com.lightningkite.services.data.Data.Bytes(json.encodeToByteArray()), com.lightningkite.MediaType.Application.Json)
                )
            }

            override suspend fun onSchedule() {}
        }
}
