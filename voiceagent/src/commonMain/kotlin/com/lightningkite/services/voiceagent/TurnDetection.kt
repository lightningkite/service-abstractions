package com.lightningkite.services.voiceagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for detecting when the user has finished speaking.
 *
 * Turn detection determines when the voice agent should start responding.
 * This is critical for natural conversation flow.
 *
 * ## Modes
 *
 * - **None**: Manual mode. Client must explicitly signal when user is done (via [VoiceAgentSession.commitAudio]).
 * - **ServerVAD**: Server-side Voice Activity Detection. Server detects silence to determine turn end.
 * - **SemanticVAD**: AI-powered detection that understands when user has finished their thought.
 *
 * ## Interruption Handling
 *
 * When [interruptResponse] is true (default), the agent will stop speaking if the user
 * starts talking. This enables natural "barge-in" behavior.
 */
@Serializable
public sealed class TurnDetection {
    /**
     * Manual turn detection. Client controls when to commit audio.
     *
     * Use this when:
     * - You have your own VAD implementation
     * - You want push-to-talk behavior
     * - You need precise control over turn boundaries
     */
    @Serializable
    @SerialName("none")
    public data object None : TurnDetection()

    /**
     * Server-side Voice Activity Detection.
     *
     * The server monitors audio input for speech and silence.
     * When silence is detected for [silenceDurationMs], the turn ends.
     *
     * @property threshold VAD activation threshold (0.0-1.0). Higher = requires louder speech.
     * @property prefixPaddingMs Audio to include before speech detection (captures start of words).
     * @property silenceDurationMs Duration of silence to end turn (shorter = faster responses, but may cut off).
     * @property createResponse Whether to automatically create a response when turn ends.
     * @property interruptResponse Whether user speech should interrupt agent speech.
     */
    @Serializable
    @SerialName("server_vad")
    public data class ServerVAD(
        val threshold: Double = 0.5,
        val prefixPaddingMs: Int = 300,
        val silenceDurationMs: Int = 500,
        val createResponse: Boolean = true,
        val interruptResponse: Boolean = true,
    ) : TurnDetection()

    /**
     * Semantic Voice Activity Detection (AI-powered).
     *
     * Uses AI to understand when the user has completed their thought,
     * rather than just detecting silence. This handles:
     * - Pauses for thinking
     * - Trailing off mid-sentence
     * - Natural conversation patterns
     *
     * @property eagerness How quickly to respond. LOW waits longer, HIGH responds faster.
     * @property createResponse Whether to automatically create a response when turn ends.
     * @property interruptResponse Whether user speech should interrupt agent speech.
     */
    @Serializable
    @SerialName("semantic_vad")
    public data class SemanticVAD(
        val eagerness: Eagerness = Eagerness.AUTO,
        val createResponse: Boolean = true,
        val interruptResponse: Boolean = true,
    ) : TurnDetection()

    /**
     * Eagerness level for semantic VAD.
     */
    @Serializable
    public enum class Eagerness {
        /** Wait longer before responding; good for thoughtful conversations */
        LOW,
        /** Balanced response timing */
        AUTO,
        /** Respond quickly; good for rapid Q&A */
        HIGH,
    }
}
