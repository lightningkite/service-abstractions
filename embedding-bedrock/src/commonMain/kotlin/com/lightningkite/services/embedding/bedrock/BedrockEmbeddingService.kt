package com.lightningkite.services.embedding.bedrock

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Embedding
import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingModelInfo
import com.lightningkite.services.embedding.EmbeddingResult
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.EmbeddingUsage
import com.lightningkite.services.http.client
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private val logger = KotlinLogging.logger("BedrockEmbeddingService")

/**
 * [EmbeddingService] implementation that talks directly to Amazon Bedrock's invoke
 * endpoint over raw ktor HTTP with AWS SigV4 signing.
 *
 * Supports both Amazon Titan and Cohere embedding model families:
 * - Titan models: one text per request (internally loops for batches)
 * - Cohere models: native batch support
 *
 * @property region AWS region the Bedrock endpoint lives in.
 * @property credentials Credentials used to sign requests.
 */
public class BedrockEmbeddingService(
    override val name: String,
    override val context: SettingContext,
    public val region: String,
    internal val credentials: AwsCredentials,
) : EmbeddingService {

    private val httpClient: HttpClient = client.config { /* no extra config needed */ }

    private val host: String = "bedrock-runtime.$region.amazonaws.com"

    override suspend fun getModels(): List<EmbeddingModelInfo> =
        KNOWN_MODELS.map { it.copy(id = it.id.copy(access = name)) }

    override suspend fun embed(texts: List<String>, model: EmbeddingModelId): EmbeddingResult {
        if (texts.isEmpty()) {
            return EmbeddingResult(embeddings = emptyList(), usage = EmbeddingUsage(inputTokens = 0))
        }

        return try {
            val modelId = model.id
            when {
                modelId.startsWith("cohere.") -> embedCohere(texts, modelId)
                else -> embedTitan(texts, modelId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbeddingException) {
            throw e
        } catch (e: Throwable) {
            throw EmbeddingException.Transport(e.message ?: "Transport failure", e)
        }
    }

    /**
     * Embed using a Titan model. Titan only accepts one text per request, so we loop
     * over each input and aggregate the results.
     */
    private suspend fun embedTitan(texts: List<String>, modelId: String): EmbeddingResult {
        val allEmbeddings = mutableListOf<Embedding>()
        var totalTokens = 0

        for (text in texts) {
            val body = if (modelId.contains("v2"))
                buildTitanV2RequestBody(text)
            else
                buildTitanV1RequestBody(text)

            val responseBody = invokeModel(modelId, body)
            val parsed = parseTitanResponse(responseBody)

            allEmbeddings.add(Embedding(FloatArray(parsed.embedding.size) { parsed.embedding[it] }))
            totalTokens += parsed.inputTextTokenCount
        }

        return EmbeddingResult(
            embeddings = allEmbeddings,
            usage = EmbeddingUsage(inputTokens = totalTokens),
        )
    }

    /**
     * Embed using a Cohere model. Cohere natively supports batching.
     */
    private suspend fun embedCohere(texts: List<String>, modelId: String): EmbeddingResult {
        // Cohere on Bedrock supports up to 2048 texts per request in practice,
        // but we use a conservative batch size.
        val maxBatchSize = 96
        if (texts.size > maxBatchSize) {
            val allEmbeddings = mutableListOf<Embedding>()
            for (chunk in texts.chunked(maxBatchSize)) {
                val result = embedCohereSingle(chunk, modelId)
                allEmbeddings.addAll(result)
            }
            return EmbeddingResult(
                embeddings = allEmbeddings,
                // Cohere on Bedrock does not report token counts
                usage = EmbeddingUsage(inputTokens = 0),
            )
        }
        val embeddings = embedCohereSingle(texts, modelId)
        return EmbeddingResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(inputTokens = 0),
        )
    }

    private suspend fun embedCohereSingle(texts: List<String>, modelId: String): List<Embedding> {
        val body = buildCohereRequestBody(texts)
        val responseBody = invokeModel(modelId, body)
        val parsed = parseCohereResponse(responseBody)
        return parsed.embeddings.map { row ->
            Embedding(FloatArray(row.size) { row[it] })
        }
    }

    /**
     * Send a signed POST to `POST /model/{modelId}/invoke` and return the response body.
     */
    private suspend fun invokeModel(modelId: String, jsonBody: String): String {
        val bodyBytes = jsonBody.encodeToByteArray()
        val path = "/model/${encodePathSegment(modelId)}/invoke"
        val amzDate = nowAmzDate()

        val signed = SigV4.signHeaders(
            method = "POST",
            host = host,
            path = path,
            query = "",
            headers = mapOf("content-type" to "application/json"),
            body = bodyBytes,
            credentials = credentials,
            region = region,
            service = "bedrock",
            amzDate = amzDate,
            includeContentSha256Header = true,
        )

        val response = httpClient.preparePost("https://$host$path") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            addSigned(signed)
            setBody(bodyBytes)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val bodyText = response.bodyAsText()
                val (awsType, awsMessage) = parseAwsErrorBody(bodyText)
                throw mapBedrockEmbeddingError(
                    type = awsType,
                    message = awsMessage ?: bodyText.take(500),
                    status = response.status,
                    modelId = EmbeddingModelId(modelId),
                )
            }
            response.bodyAsText()
        }

        return response
    }

    private fun HttpRequestBuilder.addSigned(signed: Map<String, String>) {
        for ((k, v) in signed) headers.append(k, v)
    }

    override suspend fun healthCheck(): HealthStatus {
        // No cheap read-only Bedrock endpoint without extra IAM permissions.
        // Return OK; real errors surface on first embed call.
        return HealthStatus(HealthStatus.Level.OK)
    }

    public companion object {
        init {
            if (!EmbeddingService.Settings.supports("bedrock")) {
                registerBedrockEmbeddingUrlScheme()
            }
        }

        /**
         * Known Bedrock embedding models with metadata.
         *
         * Prices are USD per million tokens per AWS public pricing.
         */
        public val KNOWN_MODELS: List<EmbeddingModelInfo> = listOf(
            EmbeddingModelInfo(
                id = EmbeddingModelId("amazon.titan-embed-text-v2:0"),
                name = "Amazon Titan Text Embeddings V2",
                description = "Amazon's latest text embedding model with configurable dimensions.",
                dimensions = 1024,
                maxInputTokens = 8192,
                usdPerMillionTokens = 0.02,
            ),
            EmbeddingModelInfo(
                id = EmbeddingModelId("amazon.titan-embed-text-v1"),
                name = "Amazon Titan Text Embeddings V1",
                description = "Amazon's first-generation text embedding model.",
                dimensions = 1536,
                maxInputTokens = 8192,
                usdPerMillionTokens = 0.10,
            ),
            EmbeddingModelInfo(
                id = EmbeddingModelId("amazon.titan-embed-image-v1"),
                name = "Amazon Titan Multimodal Embeddings",
                description = "Multimodal embedding model supporting text and images.",
                dimensions = 1024,
                maxInputTokens = 128,
                usdPerMillionTokens = 0.80,
            ),
            EmbeddingModelInfo(
                id = EmbeddingModelId("cohere.embed-english-v3"),
                name = "Cohere Embed English V3",
                description = "Cohere's English-only embedding model with batch support.",
                dimensions = 1024,
                maxInputTokens = 512,
                usdPerMillionTokens = 0.10,
            ),
            EmbeddingModelInfo(
                id = EmbeddingModelId("cohere.embed-multilingual-v3"),
                name = "Cohere Embed Multilingual V3",
                description = "Cohere's multilingual embedding model with batch support.",
                dimensions = 1024,
                maxInputTokens = 512,
                usdPerMillionTokens = 0.10,
            ),
        )
    }
}

/**
 * URL-encode a single path segment per AWS canonical URI rules.
 * Unreserved chars plus `:` pass through (Bedrock model IDs contain `:`).
 */
internal fun encodePathSegment(segment: String): String {
    val sb = StringBuilder(segment.length)
    for (b in segment.encodeToByteArray()) {
        val c = b.toInt().toChar()
        when {
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                    c == '-' || c == '.' || c == '_' || c == '~' || c == ':' -> sb.append(c)
            else -> {
                sb.append('%')
                sb.append(HEX_UP[(b.toInt() ushr 4) and 0x0f])
                sb.append(HEX_UP[b.toInt() and 0x0f])
            }
        }
    }
    return sb.toString()
}

private val HEX_UP = "0123456789ABCDEF".toCharArray()

/**
 * Return the current wall-clock timestamp formatted as `YYYYMMDDTHHMMSSZ` for SigV4.
 */
internal fun nowAmzDate(): String {
    val now = Clock.System.now()
    val parts = now.toLocalDateTime(TimeZone.UTC)
    return buildString {
        append(parts.year.toString().padStart(4, '0'))
        append((parts.month.ordinal + 1).toString().padStart(2, '0'))
        append(parts.day.toString().padStart(2, '0'))
        append('T')
        append(parts.hour.toString().padStart(2, '0'))
        append(parts.minute.toString().padStart(2, '0'))
        append(parts.second.toString().padStart(2, '0'))
        append('Z')
    }
}
