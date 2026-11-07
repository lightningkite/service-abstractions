package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmStreamEvent
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise

@JsModule("@huggingface/transformers")
@JsNonModule
private external object Transformers {
    val env: dynamic
    fun pipeline(task: String, model: String, options: dynamic = definedExternally): Promise<dynamic>

    class TextStreamer(tokenizer: dynamic, options: dynamic)
    class InterruptableStoppingCriteria {
        fun interrupt()
    }
}

/**
 * Web implementation using Transformers.js: ONNX models run in the browser on WebGPU when available, otherwise WASM.
 * [EmbeddedEngineConfig.modelPath] is a HuggingFace model ID or URL; the browser caches the download.
 */
internal actual class EmbeddedLlmEngine actual constructor(
    private val config: EmbeddedEngineConfig,
) : ChatEngine {
    private var pipe: dynamic = null

    private val progress = MutableStateFlow<Float?>(null)
    override val loadProgress: StateFlow<Float?> = progress

    override suspend fun loadModel() {
        Transformers.env.allowRemoteModels = true
        config.runtimeUrl?.let { Transformers.env.backends.onnx.wasm.wasmPaths = it }
        // Progress arrives per file; the model is several files downloaded in parallel.
        val bytesByFile = HashMap<String, Pair<Double, Double>>()
        val options = js("{}")
        options["dtype"] = config.dtype
        val device = config.device ?: if (hasWebGpuAdapter()) "webgpu" else "wasm"
        options["device"] = device
        options["progress_callback"] = { info: dynamic ->
            if (info.status == "progress") {
                bytesByFile[info.file as String] = (info.loaded as Number).toDouble() to (info.total as Number).toDouble()
                progress.value = (bytesByFile.values.sumOf { it.first } / bytesByFile.values.sumOf { it.second }).toFloat()
            }
        }
        val loaded = Transformers.pipeline("text-generation", config.modelPath, options).await()
        checkNotNull(loaded?.tokenizer) { "Transformers.js didn't return a text-generation pipeline for ${config.modelPath} on $device" }
        pipe = loaded
        progress.value = 1f
    }

    override suspend fun unloadModel() {
        (pipe?.dispose() as Promise<dynamic>?)?.await()
        pipe = null
    }

    override fun isModelLoaded(): Boolean = pipe != null

    // Transformers.js streams a thinking model's reasoning as ordinary text, so none is reported separately.
    override suspend fun supportsReasoning(): Boolean = false

    override suspend fun generate(
        systemPrompt: String?,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        enableThinking: Boolean?,
        onOutput: (LlmStreamEvent) -> Boolean,
    ): GenerationResult {
        val currentPipe = pipe ?: throw IllegalStateException("Model not loaded. Call connect() first.")
        val messages = buildList {
            if (systemPrompt != null) add(chatMessage("system", systemPrompt))
            turns.forEach { add(chatMessage(if (it.fromUser) "user" else "assistant", it.text)) }
        }.toTypedArray()

        // Chat templates that support thinking (like Qwen3's) read this variable.
        val templateVariables = js("{}")
        if (enableThinking != null) templateVariables["enable_thinking"] = enableThinking

        val templateOptions = js("{}")
        if (enableThinking != null) templateOptions["enable_thinking"] = enableThinking
        templateOptions["add_generation_prompt"] = true
        templateOptions["return_tensor"] = false
        templateOptions["return_dict"] = false
        val inputTokens = (currentPipe.tokenizer.apply_chat_template(messages, templateOptions) as Array<*>).size

        val stopping = Transformers.InterruptableStoppingCriteria()
        var outputTokens = 0
        val streamerOptions = js("{}")
        streamerOptions["skip_prompt"] = true
        streamerOptions["skip_special_tokens"] = true
        streamerOptions["token_callback_function"] = { _: dynamic -> outputTokens++ }
        streamerOptions["callback_function"] = { text: String ->
            if (!onOutput(LlmStreamEvent.TextDelta(text))) stopping.interrupt()
        }

        // Without a cap Transformers.js stops at the model's tiny default max_length.
        val maxNewTokens = maxTokens ?: 1024
        val options = js("{}")
        options["max_new_tokens"] = maxNewTokens
        options["streamer"] = Transformers.TextStreamer(currentPipe.tokenizer, streamerOptions)
        options["stopping_criteria"] = stopping
        options["tokenizer_encode_kwargs"] = templateVariables
        try {
            (currentPipe(messages, options) as Promise<dynamic>).await()
        } catch (e: CancellationException) {
            stopping.interrupt()
            throw e
        }
        return GenerationResult(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            hitMaxTokens = outputTokens >= maxNewTokens,
        )
    }
}

private fun chatMessage(role: String, content: String): dynamic {
    val message = js("{}")
    message["role"] = role
    message["content"] = content
    return message
}

// navigator.gpu can exist without a usable GPU (headless browsers, blocklisted drivers), so ask for an adapter the
// way ONNX Runtime will (Transformers.js sets it to prefer high performance).
private suspend fun hasWebGpuAdapter(): Boolean {
    val gpu: dynamic = js("typeof navigator !== 'undefined' ? navigator.gpu : undefined")
    if (gpu == undefined) return false
    val options = js("{}")
    options["powerPreference"] = "high-performance"
    return (gpu.requestAdapter(options) as Promise<dynamic>).await() != null
}
