package com.lightningkite.services.ai.ollama

import com.lightningkite.services.ai.LlmAccess
import kotlinx.coroutines.runBlocking

/**
 * Registers the `ollama://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * - `ollama://?baseUrl=http://localhost:11434` — access only; no model bound to the URL.
 * - `ollama://<model>?baseUrl=...&autoStart=true&autoPull=true` — a model id is required
 *   only when `autoStart` and/or `autoPull` is set, since that model is what gets started
 *   and/or pulled during settings instantiation. It is otherwise unused: which model to run
 *   inference against is a [com.lightningkite.services.ai.LlmModelId] passed to
 *   [com.lightningkite.services.ai.LlmAccess.stream] per call.
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
            val baseUrl = params["baseUrl"]?.let(::resolveEnvVars) ?: "http://localhost:11434"
            val autoStart = params["autoStart"]?.toBooleanStrictOrNull() ?: false
            val autoPull = params["autoPull"]?.toBooleanStrictOrNull() ?: false

            if (autoStart || autoPull) {
                if (model.isEmpty()) {
                    throw IllegalArgumentException(
                        "Ollama URL must include a model id when autoStart/autoPull is set: " +
                            "ollama://<model>?autoStart=true",
                    )
                }
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
