package com.lightningkite.services.voiceagent

import kotlinx.serialization.Serializable

/**
 * Audio encoding formats supported by voice agent providers.
 *
 * Different providers support different formats:
 * - OpenAI Realtime: PCM16 at 24kHz
 * - Google Gemini Live: PCM16 at 16kHz input, 24kHz output
 * - Telephony (Twilio): G.711 μ-law at 8kHz
 *
 * When bridging phone calls with voice agents, audio format conversion
 * is typically required (e.g., μ-law 8kHz → PCM16 24kHz).
 */
@Serializable
public enum class AudioFormat(
    public val sampleRate: Int,
    public val encoding: AudioEncoding,
    public val channels: Int = 1,
    public val bitsPerSample: Int = 16,
) {
    /** 16-bit PCM, 24kHz mono - OpenAI Realtime default */
    PCM16_24K(24000, AudioEncoding.PCM16),

    /** 16-bit PCM, 16kHz mono - Google Gemini input format */
    PCM16_16K(16000, AudioEncoding.PCM16),

    /** 16-bit PCM, 8kHz mono - Low bandwidth option */
    PCM16_8K(8000, AudioEncoding.PCM16),

    /** G.711 μ-law, 8kHz mono - Telephony standard (North America, Japan) */
    G711_ULAW(8000, AudioEncoding.G711_ULAW, bitsPerSample = 8),

    /** G.711 A-law, 8kHz mono - Telephony standard (Europe, most of world) */
    G711_ALAW(8000, AudioEncoding.G711_ALAW, bitsPerSample = 8);

    /** Bytes per second for this format */
    public val bytesPerSecond: Int get() = sampleRate * channels * (bitsPerSample / 8)
}

/**
 * Audio encoding types.
 */
@Serializable
public enum class AudioEncoding {
    /** Linear PCM 16-bit signed little-endian */
    PCM16,
    /** G.711 μ-law companding */
    G711_ULAW,
    /** G.711 A-law companding */
    G711_ALAW,
}
