package com.lightningkite.services.embedding.ollama

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
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException

private val logger = KotlinLogging.logger("OllamaEmbeddingService")

/**
 * [EmbeddingService] implementation talking to a local (or remote) Ollama server
 * via its `/api/embed` endpoint.
 *
 * Construction parameters map to the `ollama://<model>?...` URL scheme -- see
 * [OllamaEmbeddingSchemeRegistrar] for URL parsing.
 *
 * @param name Service instance name (for logs / telemetry).
 * @param baseUrl Ollama HTTP base URL, e.g. "http://localhost:11434".
 * @param context [SettingContext] for serializer resolution and telemetry.
 * @param httpClient Optional pre-configured Ktor client. If null, a dedicated one is created
 *   and closed on [disconnect].
 */
public class OllamaEmbeddingService(
    override val name: String,
    public val baseUrl: String,
    override val context: SettingContext,
    httpClient: HttpClient? = null,
) : EmbeddingService {

    public companion object {
        init {
            OllamaEmbeddingSchemeRegistrar.ensureRegistered()
        }
    }

    private val ownsClient: Boolean = httpClient == null

    private val client: HttpClient = httpClient ?: HttpClient {
        install(ContentNegotiation) {
            json(ollamaEmbeddingJson)
        }
        install(HttpTimeout) {
            // Model loading can take a while; set generous timeouts.
            requestTimeoutMillis = 10 * 60 * 1000
            socketTimeoutMillis = 10 * 60 * 1000
            connectTimeoutMillis = 30 * 1000
        }
    }

    override suspend fun disconnect() {
        if (ownsClient) client.close()
    }

    override suspend fun healthCheck(): HealthStatus = try {
        val r = client.get("$baseUrl/api/tags")
        if (r.status.isSuccess()) HealthStatus(HealthStatus.Level.OK)
        else HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "HTTP ${r.status}")
    } catch (e: Exception) {
        HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
    }

    override suspend fun getModels(): List<EmbeddingModelInfo> = wrapTransport {
        val response = client.get("$baseUrl/api/tags")
        if (!response.status.isSuccess()) {
            throw mapOllamaEmbeddingError(response.status, response.bodyAsText(), response)
        }
        val tags: OllamaTagsResponse = ollamaEmbeddingJson.decodeFromString(
            OllamaTagsResponse.serializer(),
            response.bodyAsText(),
        )
        tags.models.map { entry ->
            EmbeddingModelInfo(
                id = EmbeddingModelId(id = entry.name, access = this.name),
                name = entry.name,
                description = entry.details?.parameter_size?.let { "Ollama $it model" },
                // Ollama does not report embedding dimensions in /api/tags; null = unknown.
                dimensions = null,
                maxInputTokens = null,
                // Local inference is free.
                usdPerMillionTokens = 0.0,
            )
        }
    }

    override suspend fun embed(texts: List<String>, model: EmbeddingModelId): EmbeddingResult {
        if (texts.isEmpty()) {
            return EmbeddingResult(embeddings = emptyList(), usage = EmbeddingUsage(inputTokens = 0))
        }

        return wrapTransport {
            val request = OllamaEmbedRequest(
                model = model.id,
                input = texts,
            )

            val response = client.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(ollamaEmbeddingJson.encodeToString(OllamaEmbedRequest.serializer(), request))
            }

            if (!response.status.isSuccess()) {
                throw mapOllamaEmbeddingError(
                    status = response.status,
                    body = response.bodyAsText(),
                    response = response,
                    modelId = model,
                )
            }

            val body = ollamaEmbeddingJson.decodeFromString(
                OllamaEmbedResponse.serializer(),
                response.bodyAsText(),
            )

            val embeddings = body.embeddings.map { doubles ->
                Embedding(FloatArray(doubles.size) { doubles[it].toFloat() })
            }

            EmbeddingResult(
                embeddings = embeddings,
                usage = EmbeddingUsage(inputTokens = body.promptEvalCount),
            )
        }
    }

    /**
     * Wraps [block], letting [EmbeddingException] and [CancellationException] pass through
     * unchanged but wrapping other throwables as [EmbeddingException.Transport]. DNS /
     * connection-refused / socket errors are the dominant failure mode for Ollama since
     * it is typically local.
     */
    private inline fun <T> wrapTransport(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: EmbeddingException) {
        throw e
    } catch (e: Throwable) {
        throw EmbeddingException.Transport("Could not reach Ollama at $baseUrl", e)
    }
}
