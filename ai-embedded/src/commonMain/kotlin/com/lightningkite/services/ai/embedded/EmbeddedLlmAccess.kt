package com.lightningkite.services.ai.embedded

import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LlmAccess] implementation that runs inference on-device using platform-native ML engines.
 *
 * No network calls are made — all computation happens locally. This is suitable for
 * offline-capable, low-latency, privacy-preserving use cases with small models (1-7B params).
 *
 * Capabilities are limited compared to cloud providers:
 * - Text-in / text-out only (no image, audio, or video)
 * - No tool calling support
 * - No reasoning/chain-of-thought output
 * - Token usage is estimated, not exact
 */
public class EmbeddedLlmAccess internal constructor(
    override val name: String,
    override val context: SettingContext,
    private val config: EmbeddedEngineConfig,
) : LlmAccess {

    private val engine = EmbeddedLlmEngine(config)
    private val chatTemplate = PromptFormatter.templateFor(config.modelName)

    override suspend fun getModels(): List<LlmModelInfo> = listOf(
        LlmModelInfo(
            id = LlmModelId(id = config.modelName, access = name),
            name = config.modelName,
            description = "On-device model: ${config.modelName}",
            usdPerMillionInputTokens = 0.0,
            usdPerMillionOutputTokens = 0.0,
            roughIntelligenceRanking = 0.2,
            supportsToolCalling = false,
            supportsImageInput = false,
            supportsVideoInput = false,
            supportsAudioInput = false,
            supportsImageOutput = false,
            supportsAudioOutput = false,
            supportsReasoning = false,
            maxContextTokens = config.contextSize,
        )
    )

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
        val formattedPrompt = PromptFormatter.format(prompt, chatTemplate)
        val maxTokens = prompt.maxTokens ?: 1024
        val temperature = prompt.temperature ?: 0.7

        return flow {
            val outputTokens = StringBuilder()
            var stopped = false

            engine.generate(
                text = formattedPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                stopSequences = prompt.stopSequences,
            ).collect { token ->
                outputTokens.append(token)
                emit(LlmStreamEvent.TextDelta(token))
            }

            // Determine stop reason: if output hit maxTokens worth of text, it was likely truncated.
            // This is a rough heuristic since on-device engines may not report exact token counts.
            val stopReason = if (outputTokens.length >= maxTokens * 3) {
                LlmStopReason.MaxTokens
            } else {
                LlmStopReason.EndTurn
            }

            emit(
                LlmStreamEvent.Finished(
                    stopReason = stopReason,
                    usage = LlmUsage(
                        inputTokens = estimateTokens(formattedPrompt),
                        outputTokens = estimateTokens(outputTokens.toString()),
                    )
                )
            )
        }
    }

    override suspend fun connect() {
        engine.loadModel()
    }

    override suspend fun disconnect() {
        engine.unloadModel()
    }

    override suspend fun healthCheck(): HealthStatus = if (engine.isModelLoaded()) {
        HealthStatus(level = HealthStatus.Level.OK)
    } else {
        HealthStatus(
            level = HealthStatus.Level.ERROR,
            additionalMessage = "Model '${config.modelName}' is not loaded"
        )
    }
}

/** Rough token estimate: ~4 characters per token for English text. */
private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
