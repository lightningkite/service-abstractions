package com.lightningkite.services.embedding.ollama

import com.lightningkite.services.embedding.EmbeddingService
import kotlinx.coroutines.runBlocking

/**
 * Registers the `ollama://` URL scheme on [EmbeddingService.Settings].
 *
 * URL format:
 * ```
 * ollama://<model>?baseUrl=http://localhost:11434&autoStart=false&autoPull=false
 * ```
 *
 * Parameters:
 * - `baseUrl` — Ollama HTTP base URL. Defaults to `http://localhost:11434`. Supports `${VAR}` env-var refs.
 * - `autoStart` — If true, starts a local `ollama serve` process when the server is not running.
 * - `autoPull` — If true, pulls the named model when it is not installed locally.
 *
 * Examples:
 * ```
 * ollama://nomic-embed-text
 * ollama://nomic-embed-text?baseUrl=${OLLAMA_BASE_URL}&autoPull=true
 * ```
 */
public object OllamaEmbeddingSchemeRegistrar {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {}

    init {
        EmbeddingService.Settings.register("ollama") { name, url, context ->
            val params = parseUrlParams(url)
            val model = url.substringAfter("://", "").substringBefore("?").trim()
            if (model.isEmpty()) {
                throw IllegalArgumentException("Ollama embedding URL is missing a model: $url")
            }
            val baseUrl = params["baseUrl"]?.let(::resolveEnvVars) ?: "http://localhost:11434"
            val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: false
            val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: false

            if (autoStart || autoPull) {
                val manager = OllamaEmbeddingManager(baseUrl)
                try {
                    runBlocking {
                        manager.ensureReady(
                            model = model,
                            startServer = autoStart,
                            pullModel = autoPull,
                        )
                    }
                } finally {
                    manager.close()
                }
            }

            OllamaEmbeddingService(
                name = name,
                baseUrl = baseUrl,
                context = context,
            )
        }
    }
}

/** Parse `?key=value&key=value` query params from a URL fragment. */
internal fun parseUrlParams(url: String): Map<String, String> {
    val queryString = url.substringAfter("?", "")
    if (queryString.isEmpty()) return emptyMap()
    return queryString.split("&")
        .mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
}

/** Expand `${VAR}` references against [System.getenv]. */
internal fun resolveEnvVars(value: String): String {
    val pattern = Regex("""\$\{([^}]+)\}""")
    return pattern.replace(value) { match ->
        System.getenv(match.groupValues[1]) ?: match.value
    }
}
