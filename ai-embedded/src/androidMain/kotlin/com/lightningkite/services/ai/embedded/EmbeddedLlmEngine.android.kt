package com.lightningkite.services.ai.embedded

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Android implementation using Google LiteRT-LM for on-device inference.
 *
 * Uses the LiteRT-LM Engine and Conversation APIs to provide streaming
 * text generation from locally loaded models.
 *
 * The model path should point to a `.litertlm` model file on the device filesystem.
 */
internal actual class EmbeddedLlmEngine actual constructor(
    private val config: EmbeddedEngineConfig,
) {
    private var engine: Engine? = null

    actual suspend fun loadModel(): Unit = withContext(Dispatchers.IO) {
        val path = config.modelPath
            ?: throw IllegalStateException("modelPath is required for Android embedded inference")

        val engineConfig = EngineConfig(
            modelPath = path,
            backend = Backend.CPU(numOfThreads = config.threads),
            maxNumTokens = config.contextSize,
        )
        val e = Engine(engineConfig)
        e.initialize()
        engine = e
    }

    actual suspend fun unloadModel() {
        engine?.close()
        engine = null
    }

    actual fun isModelLoaded(): Boolean = engine?.isInitialized() == true

    actual fun generate(
        text: String,
        maxTokens: Int,
        temperature: Double,
        stopSequences: List<String>,
    ): Flow<String> = callbackFlow {
        val currentEngine = engine ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")

        val samplerConfig = SamplerConfig(
            topK = 40,
            topP = 0.95,
            temperature = temperature,
        )

        // Use a single-turn conversation to send the pre-formatted prompt and stream back tokens.
        // The prompt text is already formatted by PromptFormatter, so we send it as a user message.
        val conversationConfig = ConversationConfig(
            samplerConfig = samplerConfig,
        )

        val conversation = currentEngine.createConversation(conversationConfig)
        val accumulated = StringBuilder()
        var stopped = false

        try {
            conversation.sendMessageAsync(text).collect { message ->
                if (stopped) return@collect
                val token = message.contents.toString()
                if (token.isNotEmpty()) {
                    accumulated.append(token)

                    // Check stop sequences against accumulated output
                    for (seq in stopSequences) {
                        if (accumulated.endsWith(seq)) {
                            // Remove the stop sequence from the last emitted chunk
                            val trimmed = token.dropLast(seq.length)
                            if (trimmed.isNotEmpty()) {
                                trySend(trimmed)
                            }
                            stopped = true
                            return@collect
                        }
                    }

                    trySend(token)
                }
            }
        } finally {
            conversation.close()
        }

        close()
        awaitClose()
    }
}
