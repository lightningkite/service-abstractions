package com.lightningkite.services.pubsub.mqtt.aws

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HttpAdapter
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.WebhookSubserviceWithResponse
import com.lightningkite.services.pubsub.mqtt.MqttAuthRequest
import com.lightningkite.services.pubsub.mqtt.MqttAuthResponse
import com.lightningkite.services.pubsub.mqtt.MqttAuthService
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.serialization.json.Json
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.Environment
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationRequest
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest
import java.net.URI

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger("AwsIotMqttAuthService")

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

    private val lambdaClient: LambdaClient = LambdaClient.builder()
        .region(Region.of(region))
        .build()

    private val tracer: Tracer? = context.openTelemetry?.getTracer("mqtt-auth-aws-iot")

    public companion object {
        init {
            MqttAuthService.Settings.register("aws-iot-auth") { name, url, context ->
                // Parse: aws-iot-auth://lambda-function-name?region=us-east-1
                val parsed = URI(url)
                val functionName = parsed.host
                val params = parsed.query?.split("&")?.mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }?.toMap() ?: emptyMap()
                val region = params["region"] ?: "us-east-1"

                AwsIotMqttAuthService(name, context, functionName, region)
            }
        }
    }

    override val onAuth: WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> =
        object : WebhookSubserviceWithResponse<MqttAuthRequest, MqttAuthResponse> {
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
                    body = TypedData(com.lightningkite.services.data.Data.Bytes(json.encodeToByteArray()), com.lightningkite.MediaType.Application.Json)
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
