package com.lightningkite.services.pubsub.mqtt

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import kotlinx.serialization.json.Json

/**
 * Test implementation that collects auth requests for verification.
 */
public class TestMqttAuthService(
    override val name: String,
    override val context: SettingContext
) : MqttAuthService {
    public val authRequests: MutableList<MqttAuthRequest> = mutableListOf()
    public var nextResponse: MqttAuthResponse = MqttAuthResponse.Deny

    override val onAuth: WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> =
        object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
            override suspend fun configureWebhook(httpUrl: String) {
                // No-op for tests
            }

            override suspend fun parse(
                queryParameters: List<Pair<String, String>>,
                headers: Map<String, List<String>>,
                body: TypedData
            ): MqttAuthRequest {
                val json = Json { ignoreUnknownKeys = true }
                val request = json.decodeFromString(MqttAuthRequest.serializer(), body.text())
                authRequests.add(request)
                return request
            }

            override suspend fun render(output: MqttAuthResponse): HttpAdapter.HttpResponseLike {
                val json = Json.encodeToString(MqttAuthResponse.serializer(), output)
                return HttpAdapter.HttpResponseLike(
                    status = 200,
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = TypedData(com.lightningkite.services.data.Data.Bytes(json.encodeToByteArray()), com.lightningkite.MediaType.Application.Json)
                )
            }

            override suspend fun onSchedule() {}
        }
}
