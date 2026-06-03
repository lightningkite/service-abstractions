package com.lightningkite.services.embedding.ollama.integration

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingService
import com.lightningkite.services.embedding.ollama.OllamaEmbeddingSchemeRegistrar
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared configuration for the `:embedding-ollama` live integration tests.
 *
 * All suites share a single [service] instance. When a local Ollama server is not
 * reachable at [baseUrl] on JVM startup, every test skips (via `servicePresent = false`)
 * rather than failing.
 *
 * Environment variables:
 * - `OLLAMA_BASE_URL` -- overrides the default `http://localhost:11434`.
 * - `OLLAMA_EMBED_MODEL` -- overrides the default `nomic-embed-text` model.
 */
internal object OllamaEmbeddingTestConfig {

    val baseUrl: String = System.getenv("OLLAMA_BASE_URL")?.trimEnd('/')
        ?: "http://localhost:11434"

    /**
     * Installed models that can produce embeddings. Ollama's `/api/tags` doesn't reliably
     * expose per-model capabilities across versions, so we identify embedding models by the
     * convention that their name contains "embed" (e.g. `nomic-embed-text`,
     * `mxbai-embed-large`), plus any model explicitly named via `OLLAMA_EMBED_MODEL`.
     *
     * A chat-only Ollama install (e.g. only `gemma`/`llama`) therefore reports no embedding
     * models, which keeps [servicePresent] false so the suite skips rather than calling
     * `/api/embed` on a model that can't embed (which would fail, not skip).
     */
    private val embeddingModelNames: List<String> by lazy {
        val configured = System.getenv("OLLAMA_EMBED_MODEL")
        installedModelNames.filter { name ->
            name.contains("embed", ignoreCase = true) ||
                (configured != null && (name.equals(configured, ignoreCase = true) || name.startsWith("$configured:")))
        }
    }

    /**
     * Preferred embedding model: `OLLAMA_EMBED_MODEL` if set, else `nomic-embed-text` when
     * installed, else the first installed embedding-capable model.
     */
    val defaultModel: EmbeddingModelId by lazy {
        val configured = System.getenv("OLLAMA_EMBED_MODEL")
        val preferred = configured ?: "nomic-embed-text"
        when {
            installedModelNames.any { it.equals(preferred, ignoreCase = true) || it.startsWith("$preferred:") } ->
                EmbeddingModelId(preferred)
            embeddingModelNames.isNotEmpty() -> EmbeddingModelId(embeddingModelNames.first())
            else -> EmbeddingModelId(preferred)
        }
    }

    /**
     * True when the Ollama server answers `GET /api/tags` within 1.5s AND has at least one
     * embedding-capable model installed. A chat-only install skips the suite.
     */
    val servicePresent: Boolean get() = embeddingModelNames.isNotEmpty()

    val installedModelNames: List<String> by lazy { probe() }

    private val context: TestSettingContext by lazy { TestSettingContext() }

    val service: EmbeddingService by lazy {
        OllamaEmbeddingSchemeRegistrar.ensureRegistered()
        EmbeddingService.Settings("ollama://${defaultModel.id}?baseUrl=$baseUrl")(
            "ollama-embedding-integration",
            context,
        )
    }

    /**
     * Probes `/api/tags` with a 1.5s timeout, returning installed model names.
     * Empty = server unreachable or no models installed -- both mean "skip tests".
     */
    private fun probe(): List<String> = runBlocking {
        val client = HttpClient {}
        try {
            val body = withTimeoutOrNull(1500) {
                val resp = client.get("$baseUrl/api/tags")
                if (resp.status.isSuccess()) resp.bodyAsText() else null
            } ?: return@runBlocking emptyList()
            // Extract "name":"..." entries without pulling a serialization dependency.
            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map {
                it.groupValues[1]
            }.toList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            client.close()
        }
    }
}
