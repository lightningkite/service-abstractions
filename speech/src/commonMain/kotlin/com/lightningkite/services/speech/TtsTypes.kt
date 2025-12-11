package com.lightningkite.services.speech

import kotlinx.serialization.Serializable

/**
 * Information about an available TTS voice.
 *
 * @property voiceId Unique identifier for this voice (provider-specific)
 * @property name Human-readable voice name (e.g., "Rachel", "Adam")
 * @property language Primary language code (BCP-47, e.g., "en-US")
 * @property gender Voice gender
 * @property description Optional description of the voice characteristics
 * @property previewUrl Optional URL to hear a sample of the voice
 * @property supportedLanguages Additional languages this voice supports
 * @property labels Provider-specific labels/tags (e.g., "accent": "american", "age": "young")
 */
@Serializable
public data class VoiceInfo(
    val voiceId: String,
    val name: String,
    val language: String,
    val gender: TtsGender = TtsGender.NEUTRAL,
    val description: String? = null,
    val previewUrl: String? = null,
    val supportedLanguages: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap()
)

/**
 * Configuration for selecting and customizing a TTS voice.
 *
 * @property voiceId Specific voice ID to use (if null, provider selects default)
 * @property language Target language code (BCP-47, e.g., "en-US", "es-MX")
 * @property gender Preferred gender when voiceId is not specified
 * @property stability Voice stability (0.0-1.0). Lower = more expressive, higher = more consistent
 * @property similarityBoost How closely to match the original voice (0.0-1.0)
 * @property style Style exaggeration (0.0-1.0). Higher = more expressive (ElevenLabs)
 * @property speakerBoost Boost voice clarity and presence (ElevenLabs)
 * @property ssml If true, treat input text as SSML markup for advanced control
 */
@Serializable
public data class TtsVoiceConfig(
    val voiceId: String? = null,
    val language: String = "en-US",
    val gender: TtsGender = TtsGender.NEUTRAL,
    val stability: Float = 0.5f,
    val similarityBoost: Float = 0.75f,
    val style: Float = 0f,
    val speakerBoost: Boolean = true,
    val ssml: Boolean = false
)

/**
 * Options for TTS synthesis.
 *
 * @property model Provider-specific model ID (e.g., "eleven_multilingual_v2", "tts-1-hd")
 * @property outputFormat Desired audio output format
 * @property speed Speech speed multiplier (0.25-4.0, 1.0 = normal)
 * @property seed Optional seed for deterministic generation (not all providers support this)
 */
@Serializable
public data class TtsSynthesisOptions(
    val model: String? = null,
    val outputFormat: AudioFormat = AudioFormat.MP3_44100_128,
    val speed: Float = 1.0f,
    val seed: Int? = null
)

/**
 * Supported audio output formats.
 *
 * Not all formats are supported by all providers. The service will
 * attempt to use the closest available format if exact match isn't available.
 *
 * @property mediaType MIME type for the audio format
 * @property sampleRate Sample rate in Hz
 * @property bitrate Bitrate in kbps (for lossy formats)
 */
@Serializable
public enum class AudioFormat(
    public val mediaType: String,
    public val sampleRate: Int,
    public val bitrate: Int? = null
) {
    /** MP3 at 44.1kHz, 128kbps - Good balance of quality and size */
    MP3_44100_128("audio/mpeg", 44100, 128),

    /** MP3 at 44.1kHz, 192kbps - Higher quality MP3 */
    MP3_44100_192("audio/mpeg", 44100, 192),

    /** Raw PCM at 16kHz - Common for speech processing */
    PCM_16000("audio/pcm", 16000),

    /** Raw PCM at 22.05kHz */
    PCM_22050("audio/pcm", 22050),

    /** Raw PCM at 24kHz - Common for TTS output */
    PCM_24000("audio/pcm", 24000),

    /** Raw PCM at 44.1kHz - CD quality */
    PCM_44100("audio/pcm", 44100),

    /** μ-law at 8kHz - Standard telephony format */
    MULAW_8000("audio/basic", 8000),

    /** Opus in OGG container at 48kHz - Modern, efficient codec */
    OGG_OPUS("audio/ogg", 48000),

    /** WAV at 44.1kHz - Uncompressed, widely compatible */
    WAV_44100("audio/wav", 44100)
}

/**
 * Voice gender preference for TTS.
 */
@Serializable
public enum class TtsGender {
    MALE,
    FEMALE,
    NEUTRAL
}
