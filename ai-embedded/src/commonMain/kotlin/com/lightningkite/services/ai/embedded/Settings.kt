package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmAccess
import io.ktor.http.Url

/**
 * Registers the `embedded://` URL scheme on [LlmAccess.Settings].
 *
 * URL format:
 * ```
 * embedded://<model-name>?path=<model>&threads=4&contextSize=4096&dtype=q4
 * ```
 *
 * Parameters:
 * - `path` — Required.  Native (Android, iOS, desktop): a `.litertlm` file on disk.  Web: a HuggingFace model ID
 *   or URL of ONNX files, which Transformers.js downloads and caches in the browser.
 * - `threads` — CPU inference threads, native only (default: 4)
 * - `contextSize` — Context window in tokens, native only (default: 4096)
 * - `dtype` — ONNX weight variant to load, web only (default: q4)
 * - `runtimeUrl` — Where the page serves onnxruntime-web's `dist` files, web only.  Without it, Transformers.js
 *   loads them from cdn.jsdelivr.net.
 * - `device` — `webgpu` or `wasm`, web only (default: WebGPU when the browser has a GPU adapter)
 *
 * Examples:
 * ```
 * embedded://gemma-3-1b?path=/data/models/Gemma3-1B-IT.litertlm
 * embedded://qwen2.5-0.5b?path=onnx-community/Qwen2.5-0.5B-Instruct&dtype=q4f16
 * ```
 */
public object EmbeddedLlmSettings {
    /** No-op call site; forces class init and therefore scheme registration. */
    public fun ensureRegistered() {}

    init {
        LlmAccess.Settings.register("embedded") { name, url, context ->
            val parsed = Url(url)
            // Not parsed.host, which lowercases.
            val modelName = url.substringAfter("://").substringBefore("?")
            require(modelName.isNotEmpty()) { "Embedded URL is missing a model name: $url" }
            val config = EmbeddedEngineConfig(
                modelName = modelName,
                modelPath = parsed.parameters["path"]
                    ?: throw IllegalArgumentException("Embedded URL is missing the 'path' parameter: $url"),
                threads = parsed.parameters["threads"]?.toInt() ?: 4,
                contextSize = parsed.parameters["contextSize"]?.toInt() ?: 4096,
                dtype = parsed.parameters["dtype"] ?: "q4",
                runtimeUrl = parsed.parameters["runtimeUrl"],
                device = parsed.parameters["device"],
            )
            EmbeddedLlmAccess(name = name, context = context, config = config)
        }
    }
}
