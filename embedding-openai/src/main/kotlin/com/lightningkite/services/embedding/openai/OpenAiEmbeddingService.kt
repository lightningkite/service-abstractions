package com.lightningkite.services.embedding.openai

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.database.Embedding
import com.lightningkite.services.embedding.EmbeddingException
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingModelInfo
import com.lightningkite.services.embedding.EmbeddingResult
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.EmbeddingUsage
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException

private val logger = KotlinLogging.logger("OpenAiEmbeddingService")

/**
 * OpenAI Embeddings API implementation of [EmbeddingService].
 *
 * Works against the public OpenAI API by default and against any OpenAI-compatible
 * server when [baseUrl] is pointed at it.
 */
public class OpenAiEmbeddingService(
    override val name: String,
    override val context: SettingContext,
    /** Base URL without trailing slash. `https://api.openai.com/v1` for the public API. */
    public val baseUrl: String,
    public val apiKey: String,
    /** Optional OpenAI organization ID, sent as the `OpenAI-Organization` header. */
    public val organization: String? = null,
    /** Optional output dimension override for v3 models that support truncation. */
    public val dimensions: Int? = null,
) : EmbeddingService {

    private val client: HttpClient = com.lightningkite.services.http.client.config {
        install(ContentNegotiation) { json(openAiEmbeddingJson) }
    }

    override suspend fun getModels(): List<EmbeddingModelInfo> {
        return OpenAiEmbeddingModelCatalog.allModels(name).map { info ->
            // If dimensions override is set and matches this model, reflect it
            if (dimensions != null && info.id.id == name) {
                info.copy(dimensions = dimensions)
            } else {
                info
            }
        }
    }

    override suspend fun embed(texts: List<String>, model: EmbeddingModelId): EmbeddingResult {
        if (texts.isEmpty()) {
            return EmbeddingResult(embeddings = emptyList(), usage = EmbeddingUsage(inputTokens = 0))
        }

        // OpenAI limits batch size to 2048 inputs per request
        val maxBatchSize = 2048
        if (texts.size > maxBatchSize) {
            return embedBatched(texts, model, maxBatchSize)
        }

        return embedSingle(texts, model)
    }

    private suspend fun embedSingle(texts: List<String>, model: EmbeddingModelId): EmbeddingResult {
        val request = EmbeddingRequest(
            model = model.id,
            input = texts,
            dimensions = dimensions,
        )
        val response = try {
            client.post("$baseUrl/embeddings") {
                authHeaders()
                contentType(ContentType.Application.Json)
                setBody(openAiEmbeddingJson.encodeToString(EmbeddingRequest.serializer(), request))
            }
        } catch (e: EmbeddingException) {
            throw e
        } catch (e: IOException) {
            throw EmbeddingException.Transport(e.message ?: "Transport failure", e)
        }

        if (!response.status.isSuccess()) {
            throw mapOpenAiEmbeddingError(
                status = response.status,
                body = response.bodyAsText(),
                response = response,
                modelId = model,
            )
        }

        val body = openAiEmbeddingJson.decodeFromString(EmbeddingResponse.serializer(), response.bodyAsText())

        // OpenAI returns embeddings sorted by index; sort to be safe
        val sorted = body.data.sortedBy { it.index }
        val embeddings = sorted.map { data ->
            Embedding(FloatArray(data.embedding.size) { data.embedding[it] })
        }

        return EmbeddingResult(
            embeddings = embeddings,
            usage = EmbeddingUsage(inputTokens = body.usage.promptTokens),
        )
    }

    private suspend fun embedBatched(texts: List<String>, model: EmbeddingModelId, batchSize: Int): EmbeddingResult {
        val allEmbeddings = mutableListOf<Embedding>()
        var totalTokens = 0

        for (chunk in texts.chunked(batchSize)) {
            val result = embedSingle(chunk, model)
            allEmbeddings.addAll(result.embeddings)
            totalTokens += result.usage.inputTokens
        }

        return EmbeddingResult(
            embeddings = allEmbeddings,
            usage = EmbeddingUsage(inputTokens = totalTokens),
        )
    }

    override suspend fun healthCheck(): HealthStatus = try {
        val response = client.get("$baseUrl/models") { authHeaders() }
        if (response.status.isSuccess()) {
            HealthStatus(HealthStatus.Level.OK)
        } else {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "HTTP ${response.status}")
        }
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders() {
        header("Authorization", "Bearer $apiKey")
        organization?.let { header("OpenAI-Organization", it) }
    }
}
