@file:OptIn(ExperimentalForeignApi::class)

package com.lightningkite.services.ai.embedded

import cnames.structs.LiteRtLmConversation
import cnames.structs.LiteRtLmEngine
import cnames.structs.LiteRtLmStreamChunk
import com.google.ai.edge.litertlm.c.*
import com.lightningkite.services.ai.LlmStreamEvent
import kotlinx.cinterop.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import platform.posix.size_tVar
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS (and macOS, for tests) implementation using LiteRT-LM's C API, the same runtime and `.litertlm` files as
 * Android and desktop.  The app must link and embed `CLiteRTLM.framework`.
 */
internal actual class EmbeddedLlmEngine actual constructor(
    private val config: EmbeddedEngineConfig,
) : ChatEngine {
    private var engine: CPointer<LiteRtLmEngine>? = null

    override val loadProgress: StateFlow<Float?> = MutableStateFlow(null)

    override suspend fun loadModel(): Unit = withContext(Dispatchers.IO) {
        memScoped {
            val settings = alloc<CPointerVar<cnames.structs.LiteRtLmEngineSettings>>()
            litert_lm_engine_settings_create(config.modelPath, "cpu", null, null, settings.ptr).orThrow("configure ${config.modelPath}")
            try {
                litert_lm_engine_settings_set_num_threads(settings.value, config.threads).orThrow("set threads")
                litert_lm_engine_settings_set_max_num_tokens(settings.value, config.contextSize).orThrow("set the context size")
                // Token counts only exist in benchmark mode.
                litert_lm_engine_settings_enable_benchmark(settings.value).orThrow("enable token counts")
                val created = alloc<CPointerVar<LiteRtLmEngine>>()
                litert_lm_engine_create(settings.value, created.ptr).orThrow("load the model at ${config.modelPath}")
                engine = created.value
            } finally {
                litert_lm_engine_settings_delete(settings.value)
            }
        }
    }

    override suspend fun unloadModel() {
        engine?.let { litert_lm_engine_delete(it) }
        engine = null
    }

    override fun isModelLoaded(): Boolean = engine != null

    override suspend fun supportsReasoning(): Boolean = withContext(Dispatchers.IO) {
        memScoped {
            val file = alloc<CPointerVar<cnames.structs.LiteRtLmLoadedFile>>()
            litert_lm_loaded_file_create(config.modelPath, file.ptr).orThrow("read ${config.modelPath}")
            try {
                val supported = alloc<BooleanVar>()
                litert_lm_loaded_file_supports_thinking(file.value, supported.ptr).orThrow("read whether the model thinks")
                supported.value
            } finally {
                litert_lm_loaded_file_delete(file.value)
            }
        }
    }

    override suspend fun generate(
        systemPrompt: String?,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        enableThinking: Boolean?,
        onOutput: (LlmStreamEvent) -> Boolean,
    ): GenerationResult = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded. Call connect() first.")
        // A cancelled conversation can only be deleted, so each reply gets its own.
        val conversation = createConversation(currentEngine, systemPrompt, turns.dropLast(1))
        val stream = StreamState(conversation, onOutput)
        val streamRef = StableRef.create(stream)
        try {
            memScoped {
                val optionalArgs = alloc<CPointerVar<cnames.structs.LiteRtLmConversationOptionalArgs>>()
                litert_lm_conversation_optional_args_create(optionalArgs.ptr).orThrow("create generation options")
                val thinkingConfig = alloc<CPointerVar<cnames.structs.LiteRtLmThinkingConfig>>()
                try {
                    maxTokens?.let { litert_lm_conversation_optional_args_set_max_output_tokens(optionalArgs.value, it).orThrow("set max tokens") }
                    if (enableThinking != null) {
                        litert_lm_thinking_config_create(thinkingConfig.ptr).orThrow("create a thinking config")
                        litert_lm_thinking_config_set_enable_thinking(thinkingConfig.value, enableThinking).orThrow("set thinking")
                        litert_lm_conversation_optional_args_set_thinking_config(optionalArgs.value, thinkingConfig.value).orThrow("set thinking")
                    }
                    litert_lm_conversation_send_message_stream(
                        conversation,
                        json.encodeToString(LiteRtMessage.of("user", turns.last().text)),
                        null,
                        optionalArgs.value,
                        staticCFunction { data, chunk -> data!!.asStableRef<StreamState>().get().receive(chunk) },
                        streamRef.asCPointer(),
                    ).orThrow("start generating")
                } finally {
                    thinkingConfig.value?.let { litert_lm_thinking_config_delete(it) }
                    litert_lm_conversation_optional_args_delete(optionalArgs.value)
                }
            }
            try {
                stream.done.await()
            } catch (e: CancellationException) {
                // Deleting the conversation while native generation is still running is unsafe, so wait it out.
                stream.stoppedEarly = true
                litert_lm_conversation_cancel_process(conversation)
                withContext(NonCancellable) { stream.done.join() }
                throw e
            }
            val (inputTokens, outputTokens) = lastTurnTokenCounts(conversation)
            GenerationResult(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                hitMaxTokens = !stream.stoppedEarly && maxTokens != null && outputTokens >= maxTokens,
            )
        } finally {
            streamRef.dispose()
            litert_lm_conversation_delete(conversation)
        }
    }

    private fun createConversation(
        engine: CPointer<LiteRtLmEngine>,
        systemPrompt: String?,
        history: List<ChatTurn>,
    ): CPointer<LiteRtLmConversation> = memScoped {
        val config = alloc<CPointerVar<cnames.structs.LiteRtLmConversationConfig>>()
        litert_lm_conversation_config_create(config.ptr).orThrow("create a conversation config")
        try {
            systemPrompt?.let {
                litert_lm_conversation_config_set_system_message(config.value, json.encodeToString(LiteRtMessage.of("system", it)))
                    .orThrow("set the system prompt")
            }
            litert_lm_conversation_config_set_messages(
                config.value,
                json.encodeToString(history.map { LiteRtMessage.of(if (it.fromUser) "user" else "model", it.text) }),
            ).orThrow("set the conversation history")
            val conversation = alloc<CPointerVar<LiteRtLmConversation>>()
            litert_lm_conversation_create(engine, config.value, conversation.ptr).orThrow("create a conversation")
            conversation.value!!
        } finally {
            litert_lm_conversation_config_delete(config.value)
        }
    }

    private fun lastTurnTokenCounts(conversation: CPointer<LiteRtLmConversation>): Pair<Int, Int> = memScoped {
        val info = alloc<CPointerVar<cnames.structs.LiteRtLmBenchmarkInfo>>()
        litert_lm_conversation_get_benchmark_info(conversation, info.ptr).orThrow("read token counts")
        try {
            val turns = alloc<size_tVar>()
            val count = alloc<size_tVar>()
            litert_lm_benchmark_info_get_num_prefill_turns(info.value, turns.ptr).orThrow("read token counts")
            litert_lm_benchmark_info_get_prefill_token_count_at(info.value, turns.value - 1u, count.ptr).orThrow("read token counts")
            val prefill = count.value.toInt()
            litert_lm_benchmark_info_get_num_decode_turns(info.value, turns.ptr).orThrow("read token counts")
            litert_lm_benchmark_info_get_decode_token_count_at(info.value, turns.value - 1u, count.ptr).orThrow("read token counts")
            prefill to count.value.toInt()
        } finally {
            litert_lm_benchmark_info_delete(info.value)
        }
    }
}

/** Receives chunks on LiteRT-LM's background thread. */
private class StreamState(val conversation: CPointer<LiteRtLmConversation>, val onOutput: (LlmStreamEvent) -> Boolean) {
    val done = CompletableDeferred<Unit>()

    @Volatile
    var stoppedEarly = false

    // An exception escaping into C would kill the process, so everything is handed to the waiting coroutine instead.
    fun receive(chunk: CPointer<LiteRtLmStreamChunk>?) = memScoped {
        val isFinal = alloc<BooleanVar>()
        try {
            litert_lm_stream_chunk_is_final(chunk, isFinal.ptr).orThrow("read a chunk")
            val error = alloc<CPointerVar<ByteVar>>()
            litert_lm_stream_chunk_get_error(chunk, error.ptr).orThrow("read a chunk")
            if (error.value != null) {
                // Cancelling reports an error; that's the stop we asked for.
                if (stoppedEarly) done.complete(Unit)
                else done.completeExceptionally(IllegalStateException("LiteRT-LM: ${error.value!!.toKString()}"))
                return@memScoped
            }
            val text = alloc<CPointerVar<ByteVar>>()
            litert_lm_stream_chunk_get_text(chunk, text.ptr).orThrow("read a chunk")
            if (text.value != null && !stoppedEarly) {
                val raw = text.value!!.toKString()
                val message = try {
                    json.decodeFromString<LiteRtMessage>(raw)
                } catch (e: SerializationException) {
                    throw IllegalStateException("Unexpected LiteRT-LM chunk: $raw", e)
                }
                val outputs = listOfNotNull(
                    message.channels["thought"]?.let { LlmStreamEvent.ReasoningDelta(it) },
                    message.content.joinToString("") { it.text }.takeIf { it.isNotEmpty() }?.let { LlmStreamEvent.TextDelta(it) },
                )
                if (outputs.any { !onOutput(it) }) stop(isFinal.value)
            }
            if (isFinal.value) done.complete(Unit)
        } catch (e: Throwable) {
            stop(isFinal.value)
            done.completeExceptionally(e)
        }
    }

    // A callback may only cancel while handling a chunk that isn't the last.
    private fun stop(isFinal: Boolean) {
        stoppedEarly = true
        if (!isFinal) litert_lm_conversation_cancel_process(conversation)
    }
}

/** Throws with LiteRT-LM's message, which is thread-local, so this must run right after the failing call. */
private fun LiteRtLmStatusCode.orThrow(action: String) {
    if (this != kLiteRtLmStatusOk) {
        throw IllegalStateException("LiteRT-LM couldn't $action (status $this): ${litert_lm_get_last_error_message()?.toKString()}")
    }
}

/** LiteRT-LM's JSON message format, as its Kotlin API writes and reads it.  Reasoning arrives in the "thought" channel. */
@Serializable
private data class LiteRtMessage(
    val role: String,
    val content: List<LiteRtContent> = emptyList(),
    val channels: Map<String, String> = emptyMap(),
) {
    companion object {
        fun of(role: String, text: String) = LiteRtMessage(role, listOf(LiteRtContent(type = "text", text = text)))
    }
}

@Serializable
private data class LiteRtContent(val type: String, val text: String)

private val json = Json { ignoreUnknownKeys = true }
