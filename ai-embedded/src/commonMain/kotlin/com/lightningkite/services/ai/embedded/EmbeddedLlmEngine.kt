package com.lightningkite.services.ai.embedded

import com.lightningkite.services.ai.LlmStreamEvent
import kotlinx.coroutines.flow.StateFlow

/** Runs one on-device model.  Each engine applies its model's own chat template to the turns. */
internal interface ChatEngine {
    /** Fraction of the model downloaded so far, or null when the runtime doesn't report download progress. */
    val loadProgress: StateFlow<Float?>

    suspend fun loadModel()
    suspend fun unloadModel()
    fun isModelLoaded(): Boolean

    /** Whether the model thinks, with its reasoning reported as [LlmStreamEvent.ReasoningDelta]s.  Needn't be loaded. */
    suspend fun supportsReasoning(): Boolean

    /**
     * Generates a reply to the last turn, which is always from the user.
     * [enableThinking] turns the model's reasoning on or off; null leaves the model's default.
     * [onOutput] receives each [LlmStreamEvent.TextDelta] or [LlmStreamEvent.ReasoningDelta], possibly from another
     * thread; returning false stops generation early.
     */
    suspend fun generate(
        systemPrompt: String?,
        turns: List<ChatTurn>,
        maxTokens: Int?,
        enableThinking: Boolean?,
        onOutput: (LlmStreamEvent) -> Boolean,
    ): GenerationResult
}

/**
 * - Android and desktop: LiteRT-LM's Kotlin API
 * - iOS (and macOS, for tests): LiteRT-LM's C API
 * - Web: Transformers.js
 */
internal expect fun EmbeddedLlmEngine(config: EmbeddedEngineConfig): ChatEngine

internal data class ChatTurn(val fromUser: Boolean, val text: String)

internal data class GenerationResult(
    val inputTokens: Int,
    val outputTokens: Int,
    val hitMaxTokens: Boolean,
)
