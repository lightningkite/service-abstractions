package com.lightningkite.services.speech

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test implementation of [TextToSpeechService] that returns mock audio.
 *
 * Use this for unit tests and development. Synthesize operations return
 * minimal valid audio data that can be verified in tests.
 *
 * All synthesis requests are recorded in [synthesisHistory] for verification.
 *
 * ```kotlin
 * val tts = TestTextToSpeechService("test-tts", context)
 *
 * tts.synthesize("Hello world")
 *
 * assertEquals(1, tts.synthesisHistory.size)
 * assertEquals("Hello world", tts.synthesisHistory[0].text)
 * ```
 */
public class TestTextToSpeechService(
    override val name: String,
    override val context: SettingContext
) : TextToSpeechService {

    /**
     * History of all synthesis requests for test verification.
     */
    public val synthesisHistory: MutableList<SynthesisRequest> = mutableListOf()

    /**
     * Mock voices available in the test service.
     */
    public var mockVoices: List<VoiceInfo> = listOf(
        VoiceInfo(
            voiceId = "test-voice-1",
            name = "Test Voice 1",
            language = "en-US",
            gender = TtsGender.FEMALE,
            description = "Test female voice"
        ),
        VoiceInfo(
            voiceId = "test-voice-2",
            name = "Test Voice 2",
            language = "en-US",
            gender = TtsGender.MALE,
            description = "Test male voice"
        ),
        VoiceInfo(
            voiceId = "test-voice-es",
            name = "Test Voice ES",
            language = "es-ES",
            gender = TtsGender.FEMALE,
            description = "Test Spanish voice"
        )
    )

    /**
     * Mock audio data to return from synthesize calls.
     * Defaults to a minimal valid MP3 header.
     */
    public var mockAudioData: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00  // MP3 frame header
    )

    override suspend fun listVoices(language: String?): List<VoiceInfo> {
        return if (language != null) {
            mockVoices.filter { it.language.startsWith(language.substringBefore("-")) }
        } else {
            mockVoices
        }
    }

    override suspend fun synthesize(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): TypedData {
        synthesisHistory.add(SynthesisRequest(text, voice, options))
        return TypedData(Data.Bytes(mockAudioData), MediaType.Audio.MPEG)
    }

    override fun synthesizeStream(
        text: String,
        voice: TtsVoiceConfig,
        options: TtsSynthesisOptions
    ): Flow<TypedData> {
        synthesisHistory.add(SynthesisRequest(text, voice, options))
        return flowOf(TypedData(Data.Bytes(mockAudioData), MediaType.Audio.MPEG))
    }

    override suspend fun healthCheck(): HealthStatus {
        return HealthStatus(HealthStatus.Level.OK, additionalMessage = "Test TTS service")
    }

    /**
     * Clears synthesis history for test isolation.
     */
    public fun clearHistory() {
        synthesisHistory.clear()
    }

    /**
     * Record of a synthesis request for test verification.
     */
    public data class SynthesisRequest(
        val text: String,
        val voice: TtsVoiceConfig,
        val options: TtsSynthesisOptions
    )
}
