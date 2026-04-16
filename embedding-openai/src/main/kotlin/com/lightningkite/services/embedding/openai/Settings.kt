package com.lightningkite.services.embedding.openai

import com.lightningkite.services.embedding.EmbeddingModelId
import com.lightningkite.services.embedding.EmbeddingModelInfo
import com.lightningkite.services.embedding.EmbeddingService
import java.net.URLDecoder

/**
 * Registers the `openai://` URL scheme on [EmbeddingService.Settings].
 *
 * URL format:
 * ```
 * openai://<model-id>?apiKey=<key>&baseUrl=<url>&organization=<org>&dimensions=<n>
 * ```
 *
 * Parameters:
 * - `apiKey` — API key. Supports `${ENV_VAR}` substitution. Falls back to `OPENAI_API_KEY` env var.
 * - `baseUrl` — Optional API root override. Defaults to `https://api.openai.com/v1`.
 * - `organization` — Optional `OpenAI-Organization` header value.
 * - `dimensions` — Optional output dimension override for v3 models that support truncation.
 *
 * Examples:
 * ```
 * openai://text-embedding-3-small?apiKey=${OPENAI_API_KEY}
 * openai://text-embedding-3-large?apiKey=${OPENAI_API_KEY}&dimensions=256
 * ```
 */
public object OpenAiEmbeddingSettings {
    /** Call once at startup to guarantee the `openai` scheme is registered. Idempotent. */
    public fun ensureRegistered() {}

    init {
        EmbeddingService.Settings.register("openai") { name, url, context ->
            val params = parseUrlParams(url)
            val modelName = url.substringAfter("://", "").substringBefore("?")
            require(modelName.isNotBlank()) {
                "openai:// URL must include a model id: openai://<model>?apiKey=..."
            }
            val apiKey = params["apiKey"]?.let(::resolveEnvVars)
                ?: System.getenv("OPENAI_API_KEY")
                ?: throw IllegalArgumentException(
                    "OpenAI API key not provided in URL 'apiKey' param or OPENAI_API_KEY env var"
                )
            val baseUrl = params["baseUrl"]?.let(::resolveEnvVars)?.trimEnd('/')
                ?: "https://api.openai.com/v1"
            val organization = params["organization"]?.let(::resolveEnvVars)
            val dimensions = params["dimensions"]?.toIntOrNull()
            OpenAiEmbeddingService(
                name = name,
                context = context,
                baseUrl = baseUrl,
                apiKey = apiKey,
                organization = organization,
                dimensions = dimensions,
            )
        }
    }
}

/** Forces the `openai` scheme registration to happen on module init. */
@Suppress("unused")
private val registerOnClassLoad: Unit = OpenAiEmbeddingSettings.ensureRegistered()

/**
 * Known OpenAI embedding models with metadata (dimensions, pricing).
 *
 * Prices are USD per **million** tokens.
 * Sources: https://openai.com/api/pricing/ as of 2025-10.
 */
internal object OpenAiEmbeddingModelCatalog {
    internal data class Entry(
        val idPrefix: String,
        val displayName: String,
        val description: String?,
        val dimensions: Int,
        val maxInputTokens: Int,
        val usdPerMillionTokens: Double,
    )

    private val entries: List<Entry> = listOf(
        Entry("text-embedding-3-large", "Text Embedding 3 Large", "Most capable OpenAI embedding model", 3072, 8191, 0.13),
        Entry("text-embedding-3-small", "Text Embedding 3 Small", "Efficient OpenAI embedding model", 1536, 8191, 0.02),
        Entry("text-embedding-ada-002", "Ada v2", "Legacy OpenAI embedding model", 1536, 8191, 0.10),
    )

    fun lookup(modelId: String): Entry? =
        entries.filter { modelId.startsWith(it.idPrefix) }.maxByOrNull { it.idPrefix.length }

    fun allModels(accessName: String): List<EmbeddingModelInfo> = entries.map { entry ->
        EmbeddingModelInfo(
            id = EmbeddingModelId(id = entry.idPrefix, access = accessName),
            name = entry.displayName,
            description = entry.description,
            dimensions = entry.dimensions,
            maxInputTokens = entry.maxInputTokens,
            usdPerMillionTokens = entry.usdPerMillionTokens,
        )
    }
}

internal fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()
    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            } else {
                null
            }
        }
        .toMap()
}

/** Replaces `${VAR}` tokens with `System.getenv("VAR")`; throws if the env var is unset. */
internal fun resolveEnvVars(value: String): String {
    val envVarPattern = """\$\{([^}]+)\}""".toRegex()
    return envVarPattern.replace(value) { m ->
        val name = m.groupValues[1]
        System.getenv(name)
            ?: throw IllegalArgumentException("Environment variable \$$name not set")
    }
}
