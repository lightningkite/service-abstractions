@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.lightningkite.services.embedding.openai

import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val openAiEmbeddingJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

// --- Request DTO ---

@Serializable
internal data class EmbeddingRequest(
    val model: String,
    val input: List<String>,
    @SerialName("encoding_format") val encodingFormat: String = "float",
    val dimensions: Int? = null,
)

// --- Response DTOs ---

@Serializable
internal data class EmbeddingResponse(
    val data: List<EmbeddingData> = emptyList(),
    val model: String = "",
    val usage: EmbeddingResponseUsage = EmbeddingResponseUsage(),
)

@Serializable
internal data class EmbeddingData(
    val index: Int = 0,
    val embedding: List<Float> = emptyList(),
)

@Serializable
internal data class EmbeddingResponseUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

// --- Error mapping ---

internal fun mapOpenAiEmbeddingError(
    status: HttpStatusCode,
    body: String,
    response: HttpResponse? = null,
    modelId: EmbeddingModelId? = null,
    retryAfter: Duration? = null,
): EmbeddingException {
    val error = runCatching {
        openAiEmbeddingJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject
    }.getOrNull()
    val type = error?.get("type")?.jsonPrimitive?.content
    val code = error?.get("code")?.jsonPrimitive?.content
    val providerMessage = error?.get("message")?.jsonPrimitive?.content
    val message = providerMessage ?: "OpenAI embedding request failed with status $status"
    val effectiveRetryAfter = retryAfter ?: response?.let { parseRetryAfter(it) }

    return when (type) {
        "authentication_error", "permission_error" -> EmbeddingException.Auth(message)
        "invalid_request_error" -> when {
            // OpenAI sometimes wraps auth failures as invalid_request_error; trust HTTP status.
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                EmbeddingException.Auth(message)
            code == "model_not_found" && modelId != null ->
                EmbeddingException.InvalidModel(modelId, message)
            else -> EmbeddingException.InvalidRequest(message)
        }
        "rate_limit_error", "insufficient_quota" -> EmbeddingException.RateLimit(
            message = message,
            retryAfter = effectiveRetryAfter,
        )
        "api_error", "server_error" -> EmbeddingException.ServerError(message)
        else -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                EmbeddingException.Auth(message)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) EmbeddingException.InvalidModel(modelId, message)
                else EmbeddingException.InvalidRequest(message)
            status == HttpStatusCode.TooManyRequests ->
                EmbeddingException.RateLimit(message, retryAfter = effectiveRetryAfter)
            status.value in 400..499 -> EmbeddingException.InvalidRequest(message)
            status.value in 500..599 -> EmbeddingException.ServerError(message)
            else -> EmbeddingException.ServerError(message)
        }
    }
}

internal fun parseRetryAfter(response: HttpResponse): Duration? {
    val header = response.headers["retry-after"] ?: return null
    return header.trim().toLongOrNull()?.seconds
}
