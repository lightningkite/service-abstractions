package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAccess
import kotlinx.coroutines.runBlocking

/**
 * Registers the `ollama://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * - `ollama://<model>?baseUrl=http://localhost:11434&autoStart=false&autoPull=false`
 *
 * Resolving env-var refs: values of the form `${VAR}` are replaced with `System.getenv("VAR")`
 * if present, otherwise left unchanged.
 */
public object OllamaSchemeRegistrar {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {}

    init {
        LlmAccess.Settings.register("ollama") { name, url, context ->
            val params = parseUrlParams(url)
            val model = url.substringAfter("://", "").substringBefore("?").trim()
            if (model.isEmpty()) {
                throw IllegalArgumentException("Ollama URL is missing a model: $url")
            }
            val baseUrl = params["baseUrl"]?.let(::resolveEnvVars) ?: "http://localhost:11434"
            val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: false
            val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: false

            if (autoStart || autoPull) {
                val manager = OllamaManager(baseUrl)
                // ensureReady blocks briefly (server start-up) and potentially for a long time
                // (model download). We're in a factory so runBlocking is acceptable, but we
                // scope the manager to live only for this bootstrap.
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

            OllamaLlmAccess(
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
