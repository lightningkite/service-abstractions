package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmAccess

/**
 * Registers the `embedded://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * ```
 * embedded://<model-name>?path=/path/to/model&threads=4&contextSize=4096
 * ```
 *
 * Parameters:
 * - `path` — Model file path (native) or HuggingFace model ID / URL (web). Required.
 * - `threads` — Number of inference threads (default: 4)
 * - `contextSize` — Context window size in tokens (default: 4096)
 *
 * Examples:
 * ```
 * embedded://gemma-2b?path=/data/models/gemma-2b.tflite
 * embedded://phi-3-mini?path=Xenova/phi-3-mini&contextSize=2048
 * ```
 */
public object EmbeddedLlmSettings {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {}

    init {
        LlmAccess.Settings.register("embedded") { name, url, context ->
            val modelName = url.substringAfter("://").substringBefore("?").trim()
            if (modelName.isEmpty()) {
                throw IllegalArgumentException("Embedded URL is missing a model name: $url")
            }
            val params = parseUrlParams(url)
            val config = EmbeddedEngineConfig(
                modelName = modelName,
                modelPath = params["path"],
                threads = params["threads"]?.toIntOrNull() ?: 4,
                contextSize = params["contextSize"]?.toIntOrNull() ?: 4096,
            )
            EmbeddedLlmAccess(name = name, context = context, config = config)
        }
    }
}

/** Parse `?key=value&key=value` query params from a URL string. */
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
