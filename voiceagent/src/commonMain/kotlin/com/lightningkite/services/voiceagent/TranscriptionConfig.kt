package com.lightningkite.services.voiceagent

import kotlinx.serialization.Serializable

/**
 * Configuration for speech-to-text transcription of user input.
 *
 * When enabled, the voice agent will transcribe user speech and provide
 * it via [VoiceAgentEvent.InputTranscription] events. This is useful for:
 * - Logging conversations
 * - Displaying captions
 * - Post-processing transcripts
 *
 * Note: Transcription adds latency and cost. Only enable if you need the text.
 *
 * @property model Provider-specific transcription model. Examples:
 *   - OpenAI: "whisper-1", "gpt-4o-transcribe"
 *   - Leave null for provider default
 * @property language BCP-47 language code hint (e.g., "en", "es"). Improves accuracy.
 * @property prompt Optional context to improve transcription accuracy (e.g., domain-specific terms).
 */
@Serializable
public data class TranscriptionConfig(
    val model: String? = null,
    val language: String? = null,
    val prompt: String? = null,
)
