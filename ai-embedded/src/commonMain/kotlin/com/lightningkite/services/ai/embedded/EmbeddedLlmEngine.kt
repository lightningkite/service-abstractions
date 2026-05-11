package com.lightningkite.services.ai.embedded

import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific on-device LLM inference engine.
 *
 * Each platform provides an actual implementation backed by a native SDK:
 * - Android: LiteRT-LM
 * - iOS: Core ML
 * - JS/Web: Transformers.js
 */
internal expect class EmbeddedLlmEngine(config: EmbeddedEngineConfig) {
    /** Loads the model into memory. Must be called before [generate]. */
    suspend fun loadModel()

    /** Unloads the model from memory and releases resources. */
    suspend fun unloadModel()

    /** Whether a model is currently loaded and ready for inference. */
    fun isModelLoaded(): Boolean

    /**
     * Runs text generation, emitting tokens as they are produced.
     *
     * @param text The formatted prompt text
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0 = greedy, higher = more random)
     * @param stopSequences Sequences that terminate generation when encountered
     * @return Flow of generated token strings
     */
    fun generate(
        text: String,
        maxTokens: Int,
        temperature: Double,
        stopSequences: List<String>,
    ): Flow<String>
}
