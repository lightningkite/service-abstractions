package com.lightningkite.services.embedding.ollama

import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal val ollamaEmbeddingJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

// --- Request DTO ---

@Serializable
internal data class OllamaEmbedRequest(
    val model: String,
    val input: List<String>,
)

// --- Response DTO ---

@Serializable
internal data class OllamaEmbedResponse(
    val model: String = "",
    val embeddings: List<List<Double>> = emptyList(),
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
)

// --- Tags response (for getModels / healthCheck) ---

@Serializable
internal data class OllamaTagsResponse(
    val models: List<OllamaTagEntry> = emptyList(),
)

@Serializable
internal data class OllamaTagEntry(
    val name: String,
    val modified_at: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val details: OllamaModelDetails? = null,
)

@Serializable
internal data class OllamaModelDetails(
    val parameter_size: String? = null,
    val quantization_level: String? = null,
    val family: String? = null,
    val families: List<String>? = null,
)

// --- Error mapping ---

/**
 * Maps an Ollama HTTP error response to [EmbeddingException].
 *
 * Ollama error bodies are plain JSON `{"error": "..."}` — classification is driven by
 * HTTP status plus string matching for model-not-found cases.
 */
internal fun mapOllamaEmbeddingError(
    status: HttpStatusCode,
    body: String,
    response: HttpResponse? = null,
    modelId: EmbeddingModelId? = null,
): EmbeddingException {
    val snippet = body.take(500)
    return when {
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            EmbeddingException.Auth("Ollama auth failure (HTTP ${status.value}): $snippet")

        status == HttpStatusCode.TooManyRequests ->
            EmbeddingException.RateLimit(
                "Ollama rate limit (HTTP 429): $snippet",
                retryAfter = response?.let(::parseRetryAfter),
            )

        status == HttpStatusCode.NotFound -> {
            val lower = body.lowercase()
            if ("model" in lower || "not found" in lower) {
                val id = modelId ?: EmbeddingModelId("")
                EmbeddingException.InvalidModel(id, "Ollama model not found: $snippet")
            } else {
                EmbeddingException.InvalidRequest("Ollama returned 404: $snippet")
            }
        }

        status.value in 400..499 ->
            EmbeddingException.InvalidRequest("Ollama rejected request (HTTP ${status.value}): $snippet")

        status.value in 500..599 ->
            EmbeddingException.ServerError("Ollama server error (HTTP ${status.value}): $snippet")

        else ->
            EmbeddingException.InvalidRequest("Ollama unexpected HTTP ${status.value}: $snippet")
    }
}

private fun parseRetryAfter(response: HttpResponse): Duration? {
    val raw = response.headers[HttpHeaders.RetryAfter] ?: return null
    return raw.trim().toLongOrNull()?.seconds
}
