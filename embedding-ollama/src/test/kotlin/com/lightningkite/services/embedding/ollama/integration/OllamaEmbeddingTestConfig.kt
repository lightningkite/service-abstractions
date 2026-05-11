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
     * Preferred embedding model. If unset via env var and the default is not installed,
     * falls back to the first installed model (if any).
     */
    val defaultModel: EmbeddingModelId by lazy {
        val configured = System.getenv("OLLAMA_EMBED_MODEL")
        val preferred = configured ?: "nomic-embed-text"
        val installed = installedModelNames
        when {
            installed.any { it.equals(preferred, ignoreCase = true) || it.startsWith("$preferred:") } ->
                EmbeddingModelId(preferred)
            // User set env var but model not installed -- surface the name so failing tests
            // point the developer at the problem.
            configured != null -> EmbeddingModelId(configured)
            installed.isNotEmpty() -> EmbeddingModelId(installed.first())
            else -> EmbeddingModelId(preferred)
        }
    }

    /**
     * True when the Ollama server answers `GET /api/tags` within 1.5s AND has at least one
     * model installed.
     */
    val servicePresent: Boolean get() = installedModelNames.isNotEmpty()

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
