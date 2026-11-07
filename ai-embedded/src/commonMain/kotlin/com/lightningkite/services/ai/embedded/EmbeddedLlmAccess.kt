package com.lightningkite.services.ai.embedded

import com.lightningkite.services.SettingContext
import com.lightningkite.services.ai.*
import com.lightningkite.services.data.HealthStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

/**
 * [LlmAccess] implementation that runs inference on-device using platform-native ML engines.
 *
 * No network calls are made except to download the model on web.  Text in, text out:
 * attachments are replaced by a note, and tools are rejected.
 *
 * Call [connect] to load the model before streaming.
 */
public class EmbeddedLlmAccess internal constructor(
    override val name: String,
    override val context: SettingContext,
    internal val config: EmbeddedEngineConfig,
    private val engine: ChatEngine = EmbeddedLlmEngine(config),
) : LlmAccess {

    /** Fraction of the model downloaded during [connect], or null when the platform doesn't report it. */
    public val loadProgress: StateFlow<Float?> get() = engine.loadProgress

    override suspend fun getModels(): List<LlmModelInfo> = listOf(
        LlmModelInfo(
            id = LlmModelId(id = config.modelName, access = name),
            name = config.modelName,
            description = "On-device model: ${config.modelName}",
            usdPerMillionInputTokens = 0.0,
            usdPerMillionOutputTokens = 0.0,
            roughIntelligenceRanking = 0.2,
            supportsToolCalling = false,
            supportedMediaTypes = emptyList(),
            supportsImageOutput = false,
            supportsAudioOutput = false,
            supportsReasoning = engine.supportsReasoning(),
            maxContextTokens = config.contextSize,
        )
    )

    override suspend fun stream(model: LlmModelId, prompt: LlmPrompt): Flow<LlmStreamEvent> {
        if (prompt.tools.isNotEmpty()) throw LlmException.InvalidRequest("On-device models don't support tool calling")
        val systemPrompt = prompt.systemPrompt.joinToString("\n") { it.asText() }.ifEmpty { null }
        val turns = prompt.messages.map { message ->
            when (message) {
                is LlmMessage.User -> ChatTurn(fromUser = true, text = message.parts.joinToString("\n") { it.asText() })
                is LlmMessage.Agent -> ChatTurn(fromUser = false, text = message.parts.joinToString("") { part ->
                    when (part) {
                        is LlmPart.ContentOnly -> part.asText()
                        is LlmPart.Reasoning -> ""
                        is LlmPart.ToolCall -> throw LlmException.InvalidRequest("On-device models don't support tool calling")
                    }
                })
                is LlmMessage.ToolResult -> throw LlmException.InvalidRequest("On-device models don't support tool calling")
            }
        }
        if (turns.lastOrNull()?.fromUser != true) throw LlmException.InvalidRequest("The last message must be from the user")

        return channelFlow {
            // Text that might be the start of a stop sequence is held back until it can't be.
            val pending = StringBuilder()
            val holdBack = (prompt.stopSequences.maxOfOrNull { it.length } ?: 1) - 1
            var hitStopSequence = false
            val enableThinking = prompt.reasoningEffort?.let { it != LlmReasoningEffort.Off }
            val result = engine.generate(systemPrompt, turns, prompt.maxTokens, enableThinking) { output ->
                if (output !is LlmStreamEvent.TextDelta) {
                    trySend(output)
                    return@generate true
                }
                pending.append(output.text)
                val stopAt = prompt.stopSequences.mapNotNull { stop -> pending.indexOf(stop).takeIf { it >= 0 } }.minOrNull()
                if (stopAt != null) {
                    if (stopAt > 0) trySend(LlmStreamEvent.TextDelta(pending.substring(0, stopAt)))
                    hitStopSequence = true
                    return@generate false
                }
                val safeLength = pending.length - holdBack
                if (safeLength > 0) {
                    trySend(LlmStreamEvent.TextDelta(pending.substring(0, safeLength)))
                    pending.deleteRange(0, safeLength)
                }
                true
            }
            if (!hitStopSequence && pending.isNotEmpty()) send(LlmStreamEvent.TextDelta(pending.toString()))
            send(
                LlmStreamEvent.Finished(
                    stopReason = when {
                        hitStopSequence -> LlmStopReason.StopSequence
                        result.hitMaxTokens -> LlmStopReason.MaxTokens
                        else -> LlmStopReason.EndTurn
                    },
                    usage = LlmUsage(inputTokens = result.inputTokens, outputTokens = result.outputTokens),
                )
            )
        }.buffer(Channel.UNLIMITED)  // so trySend from the engine's callback never drops text
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

private fun LlmPart.ContentOnly.asText(): String = when (this) {
    is LlmPart.Text -> text
    is LlmPart.Attachment -> attachment.notSentNote().text
}
