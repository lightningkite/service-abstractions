package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val bedrockEmbeddingJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

// ---- Titan request/response helpers ----

/**
 * Build the JSON body for a Titan Embeddings V1 invoke call.
 * Titan V1 does not support `dimensions` or `normalize` parameters.
 */
internal fun buildTitanV1RequestBody(text: String): String =
    """{"inputText":${JsonPrimitive(text)}}"""

/**
 * Build the JSON body for a Titan Embeddings V2 invoke call.
 * V2 supports optional `dimensions` and `normalize` parameters.
 */
internal fun buildTitanV2RequestBody(text: String, dimensions: Int? = null, normalize: Boolean = true): String {
    val sb = StringBuilder()
    sb.append("""{"inputText":${JsonPrimitive(text)}""")
    if (dimensions != null) sb.append(""","dimensions":$dimensions""")
    sb.append(""","normalize":$normalize""")
    sb.append("}")
    return sb.toString()
}

/**
 * Parse a Titan embedding response.
 * Response shape: `{"embedding": [float...], "inputTextTokenCount": int}`
 */
internal fun parseTitanResponse(body: String): TitanEmbeddingResponse {
    val root = bedrockEmbeddingJson.parseToJsonElement(body).jsonObject
    val embedding = root["embedding"]!!.jsonArray.map { it.jsonPrimitive.float }
    val tokenCount = root["inputTextTokenCount"]?.jsonPrimitive?.int ?: 0
    return TitanEmbeddingResponse(embedding = embedding, inputTextTokenCount = tokenCount)
}

internal data class TitanEmbeddingResponse(
    val embedding: List<Float>,
    val inputTextTokenCount: Int,
)

// ---- Cohere request/response helpers ----

/**
 * Build the JSON body for a Cohere embed invoke call.
 * Supports batching multiple texts.
 */
internal fun buildCohereRequestBody(texts: List<String>, inputType: String = "search_document"): String {
    val textsArray = texts.joinToString(",") { JsonPrimitive(it).toString() }
    return """{"texts":[$textsArray],"input_type":${JsonPrimitive(inputType)},"truncate":"END"}"""
}

/**
 * Parse a Cohere embedding response.
 * Response shape: `{"embeddings": [[float...], ...], "texts": [...]}`.
 * Cohere does not report token counts.
 */
internal fun parseCohereResponse(body: String): CohereEmbeddingResponse {
    val root = bedrockEmbeddingJson.parseToJsonElement(body).jsonObject
    val embeddings = root["embeddings"]!!.jsonArray.map { row ->
        row.jsonArray.map { it.jsonPrimitive.float }
    }
    return CohereEmbeddingResponse(embeddings = embeddings)
}

internal data class CohereEmbeddingResponse(
    val embeddings: List<List<Float>>,
)

// ---- Error mapping ----

/**
 * Parse the AWS JSON error envelope `{"__type": "...", "message": "..."}`.
 */
internal fun parseAwsErrorBody(body: String?): Pair<String?, String?> {
    if (body.isNullOrBlank()) return null to null
    val obj = runCatching { bedrockEmbeddingJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return null to null
    val rawType = obj.str("__type") ?: obj.str("code")
    val type = rawType?.substringAfterLast('#')
    val message = obj.str("message") ?: obj.str("Message")
    return type to message
}

/**
 * Translate a Bedrock error into a typed [EmbeddingException] subclass.
 */
internal fun mapBedrockEmbeddingError(
    type: String?,
    message: String?,
    status: HttpStatusCode? = null,
    modelId: EmbeddingModelId? = null,
    cause: Throwable? = null,
): EmbeddingException {
    val msg = message?.takeIf { it.isNotBlank() }
        ?: type?.let { "Bedrock embedding request failed: $it" }
        ?: status?.let { "Bedrock embedding request failed with status $it" }
        ?: "Bedrock embedding request failed"
    return when (type) {
        "AccessDeniedException", "UnauthorizedException" ->
            EmbeddingException.Auth(msg, cause)
        "ValidationException" ->
            EmbeddingException.InvalidRequest(msg, cause)
        "ResourceNotFoundException", "ModelNotReadyException" ->
            if (modelId != null) EmbeddingException.InvalidModel(modelId, msg, cause)
            else EmbeddingException.InvalidRequest(msg, cause)
        "ThrottlingException", "TooManyRequestsException" ->
            EmbeddingException.RateLimit(message = msg, retryAfter = null, cause = cause)
        "InternalServerException",
        "InternalFailureException",
        "ServiceUnavailableException",
        "ModelErrorException",
        "ModelTimeoutException" ->
            EmbeddingException.ServerError(msg, cause)
        null, "" -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                EmbeddingException.Auth(msg, cause)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) EmbeddingException.InvalidModel(modelId, msg, cause)
                else EmbeddingException.InvalidRequest(msg, cause)
            status == HttpStatusCode.TooManyRequests ->
                EmbeddingException.RateLimit(message = msg, retryAfter = null, cause = cause)
            status != null && status.value in 400..499 ->
                EmbeddingException.InvalidRequest(msg, cause)
            status != null && status.value in 500..599 ->
                EmbeddingException.ServerError(msg, cause)
            else -> EmbeddingException.ServerError(msg, cause)
        }
        else -> when {
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
                EmbeddingException.Auth(msg, cause)
            status == HttpStatusCode.NotFound ->
                if (modelId != null) EmbeddingException.InvalidModel(modelId, msg, cause)
                else EmbeddingException.InvalidRequest(msg, cause)
            status == HttpStatusCode.TooManyRequests ->
                EmbeddingException.RateLimit(message = msg, retryAfter = null, cause = cause)
            status != null && status.value in 400..499 ->
                EmbeddingException.InvalidRequest(msg, cause)
            status != null && status.value in 500..599 ->
                EmbeddingException.ServerError(msg, cause)
            else -> EmbeddingException.ServerError(msg, cause)
        }
    }
}

// ---- JSON helpers ----

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
