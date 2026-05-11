package com.lightningkite.services.ai.embedded

import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.js.Promise

// External declarations for @huggingface/transformers npm package (v3).
// TextStreamer supports token-by-token streaming via callback_function.

@JsModule("@huggingface/transformers")
@JsNonModule
private external object Transformers {
    val env: dynamic

    fun pipeline(task: String, model: String, options: dynamic = definedExternally): Promise<dynamic>

    class TextStreamer(tokenizer: dynamic, options: TextStreamerOptions) {
        fun put(value: dynamic)
        fun end()
    }
}

private external interface TextStreamerOptions {
    var skip_prompt: Boolean
    var callback_function: ((text: String) -> Unit)?
    var token_callback_function: ((token: dynamic) -> Unit)?
    var decode_kwargs: dynamic
}

@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")
private fun textStreamerOptions(block: TextStreamerOptions.() -> Unit): TextStreamerOptions {
    val obj = js("{}") as TextStreamerOptions
    obj.block()
    return obj
}

/**
 * JS/Web implementation using Transformers.js for on-device inference.
 *
 * Runs ONNX models in the browser via WebAssembly with optional WebGPU acceleration.
 * Models are loaded from HuggingFace Hub or a custom URL.
 *
 * The `modelPath` in config should be a HuggingFace model ID (e.g., "Xenova/phi-2")
 * or a URL pointing to ONNX model files.
 */
internal actual class EmbeddedLlmEngine actual constructor(
    private val config: EmbeddedEngineConfig,
) {
    private var pipe: dynamic = null
    private var tokenizer: dynamic = null

    actual suspend fun loadModel() {
        val modelId = config.modelPath ?: config.modelName

        // Ensure remote model downloads are allowed in bundled environments.
        Transformers.env.allowRemoteModels = true

        // Load the text-generation pipeline from Transformers.js.
        // This downloads (and caches in IndexedDB) the ONNX model + tokenizer.
        // dtype "fp32" avoids requiring a quantized model variant.
        val pipelineOptions = js("{}")
        pipelineOptions["dtype"] = "fp32"
        pipe = Transformers.pipeline("text-generation", modelId, pipelineOptions).await()

        // The pipeline object exposes its tokenizer for use with TextStreamer.
        tokenizer = pipe.tokenizer
    }

    actual suspend fun unloadModel() {
        // Transformers.js pipelines can be disposed by nulling the reference.
        // The WASM memory will be reclaimed by GC.
        try {
            pipe?.dispose()
        } catch (_: Throwable) {
        }
        pipe = null
        tokenizer = null
    }

    actual fun isModelLoaded(): Boolean = pipe != null

    actual fun generate(
        text: String,
        maxTokens: Int,
        temperature: Double,
        stopSequences: List<String>,
    ): Flow<String> = callbackFlow {
        val currentPipe = pipe ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")
        val currentTokenizer = tokenizer
            ?: throw IllegalStateException("Tokenizer not available. Call loadModel() first.")

        val accumulated = StringBuilder()
        var stopped = false

        // TextStreamer decodes tokens incrementally via callback_function.
        // skip_prompt = true omits the echoed input prompt from callbacks.
        val streamer = Transformers.TextStreamer(
            currentTokenizer,
            textStreamerOptions {
                skip_prompt = true
                callback_function = { chunk ->
                    if (!stopped) {
                        // Check for stop sequences in the accumulated output
                        val stopIndex = findStopSequence(accumulated.toString() + chunk, stopSequences)
                        if (stopIndex != null) {
                            // Trim output at the stop sequence boundary
                            val alreadyEmitted = accumulated.length
                            if (stopIndex > alreadyEmitted) {
                                val partial = (accumulated.toString() + chunk).substring(alreadyEmitted, stopIndex)
                                trySend(partial)
                            }
                            stopped = true
                        } else {
                            accumulated.append(chunk)
                            trySend(chunk)
                        }
                    }
                }
            }
        )

        val options = js("{}")
        options["max_new_tokens"] = maxTokens
        options["temperature"] = temperature
        options["do_sample"] = temperature > 0.0
        options["return_full_text"] = false
        options["streamer"] = streamer

        // The pipeline call drives generation; tokens stream via the TextStreamer
        // callback above. The await completes when generation finishes.
        (currentPipe(text, options) as Promise<dynamic>).await()

        close()
        awaitClose()
    }
}

/**
 * Finds the earliest occurrence of any stop sequence in [text].
 * Returns the start index of the match, or null if none found.
 */
private fun findStopSequence(text: String, stopSequences: List<String>): Int? {
    if (stopSequences.isEmpty()) return null
    var earliest: Int? = null
    for (seq in stopSequences) {
        val idx = text.indexOf(seq)
        if (idx >= 0 && (earliest == null || idx < earliest)) {
            earliest = idx
        }
    }
    return earliest
}
