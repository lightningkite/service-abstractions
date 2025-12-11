package com.lightningkite.services.voiceagent

import kotlinx.serialization.Serializable

/**
 * Voice configuration for text-to-speech output from the voice agent.
 *
 * Each provider has different available voices. Check provider documentation
 * for the list of supported voice names.
 *
 * ## Common Voices by Provider
 *
 * **OpenAI Realtime:**
 * - alloy, ash, ballad, coral, echo, sage, shimmer, verse
 *
 * **Google Gemini Live:**
 * - Puck, Charon, Kore, Fenrir, Aoede (and more)
 *
 * **ElevenLabs:**
 * - Uses voice IDs from ElevenLabs voice library
 *
 * @property name Provider-specific voice identifier
 * @property language BCP-47 language code (e.g., "en-US", "es-MX"). Optional; defaults to provider's default.
 * @property speed Speaking speed multiplier (1.0 = normal). Range typically 0.5-2.0.
 */
@Serializable
public data class VoiceConfig(
    val name: String? = null,
    val language: String? = null,
    val speed: Double = 1.0,
)
