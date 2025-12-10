package com.lightningkite.services.voiceagent

import kotlinx.serialization.Serializable

/**
 * Configuration for a voice agent session.
 *
 * This defines how the voice agent behaves during a conversation session.
 * Configuration can be updated mid-session via [VoiceAgentSession.updateSession].
 *
 * ## Example
 *
 * ```kotlin
 * val config = VoiceAgentSessionConfig(
 *     instructions = "You are a helpful customer service agent for Acme Corp. " +
 *         "Be friendly and concise. If you don't know something, say so.",
 *     voice = VoiceConfig(name = "alloy"),
 *     turnDetection = TurnDetection.ServerVAD(silenceDurationMs = 700),
 *     inputTranscription = TranscriptionConfig(language = "en"),
 *     tools = listOf(
 *         BookAppointmentTool.descriptor,
 *         CheckOrderStatusTool.descriptor,
 *     ),
 * )
 * ```
 *
 * @property instructions System prompt that guides the agent's behavior and personality.
 * @property voice Voice configuration for the agent's speech output.
 * @property inputAudioFormat Expected format of audio input from the client.
 * @property outputAudioFormat Format for audio output from the agent.
 * @property turnDetection How to detect when the user has finished speaking.
 * @property inputTranscription Configuration for transcribing user speech. Null to disable.
 * @property tools List of tool descriptors the agent can invoke. Uses Koog's ToolDescriptor format.
 * @property temperature Sampling temperature for response generation (0.0-2.0). Higher = more creative.
 * @property maxResponseTokens Maximum tokens in a single response. Null for no limit.
 */
@Serializable
public data class VoiceAgentSessionConfig(
    val instructions: String? = null,
    val voice: VoiceConfig? = null,
    val inputAudioFormat: AudioFormat = AudioFormat.PCM16_24K,
    val outputAudioFormat: AudioFormat = AudioFormat.PCM16_24K,
    val turnDetection: TurnDetection = TurnDetection.ServerVAD(),
    val inputTranscription: TranscriptionConfig? = null,
    val tools: List<SerializableToolDescriptor> = emptyList(),
    val temperature: Double = 0.8,
    val maxResponseTokens: Int? = null,
)
