package com.lightningkite.services.speech.elevenlabs

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip integration tests for ElevenLabs TTS + STT.
 *
 * These tests use TTS to generate audio, then STT to transcribe it back.
 * This validates both services work correctly together.
 *
 * Requires ELEVENLABS_API_KEY environment variable to be set.
 */
class ElevenLabsSpeechRoundTripTest {

    private val testContext = TestSettingContext()
    private val apiKey: String? = TestConfig.elevenlabsApiKey

    private fun skipIfNoCredentials(): Boolean {
        if (apiKey == null) {
            println("Skipping live test - ELEVENLABS_API_KEY not set (check env var or local.properties)")
            return true
        }
        return false
    }

    @Test
    fun testTtsHealthCheck() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val status = tts.healthCheck()
        println("TTS Health check result: ${status.level} - ${status.additionalMessage}")
        assertEquals(HealthStatus.Level.OK, status.level, "Health check message: ${status.additionalMessage}")
    }

    @Test
    fun testSttHealthCheck() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val stt = ElevenLabsSpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val status = stt.healthCheck()
        println("STT Health check result: ${status.level} - ${status.additionalMessage}")
        assertEquals(HealthStatus.Level.OK, status.level, "Health check message: ${status.additionalMessage}")
    }

    @Test
    fun testListVoices() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        try {
            val voices = tts.listVoices()
            println("Found ${voices.size} voices")
            assertTrue(voices.isNotEmpty(), "Should return available voices")

            println("Available ElevenLabs voices:")
            voices.take(5).forEach { voice ->
                println("  - ${voice.name} (${voice.voiceId}): ${voice.gender}, ${voice.language}")
            }
        } catch (e: Exception) {
            println("listVoices failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun testRoundTrip_simplePhrase() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val stt = ElevenLabsSpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        // Get a voice to use
        val voices = tts.listVoices()
        val voiceId = voices.firstOrNull()?.voiceId

        if (voiceId == null) {
            println("No voices available, skipping test")
            return@runTest
        }

        // Generate audio from text
        val originalText = "Hello world"
        val audio = tts.synthesize(
            text = originalText,
            voice = TtsVoiceConfig(voiceId = voiceId),
            options = TtsSynthesisOptions(outputFormat = AudioFormat.MP3_44100_128)
        )

        assertTrue(audio.data.size!! > 0, "TTS should generate audio data")
        println("Generated ${audio.data.size} bytes of audio")

        // Transcribe the audio back to text
        val result = stt.transcribe(
            audio = audio,
            options = TranscriptionOptions(language = "en")
        )

        assertTrue(result.text.isNotEmpty(), "STT should return transcription")
        println("Transcription: ${result.text}")

        // Check that key words are present
        val transcribedLower = result.text.lowercase()
        assertTrue(
            transcribedLower.contains("hello") || transcribedLower.contains("world"),
            "Transcription should contain key words. Got: ${result.text}"
        )
    }

    @Test
    fun testRoundTrip_withWordTimestamps() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val stt = ElevenLabsSpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val voices = tts.listVoices()
        val voiceId = voices.firstOrNull()?.voiceId ?: return@runTest

        val audio = tts.synthesize(
            text = "One two three four five",
            voice = TtsVoiceConfig(voiceId = voiceId),
            options = TtsSynthesisOptions()
        )

        val result = stt.transcribe(
            audio = audio,
            options = TranscriptionOptions(
                language = "en",
                wordTimestamps = true
            )
        )

        assertTrue(result.text.isNotEmpty(), "Should have transcription")
        println("Transcription: ${result.text}")
        println("Words with timestamps: ${result.words.size}")

        result.words.forEach { word ->
            println("  '${word.text}' @ ${word.startTime} - ${word.endTime}")
        }
    }

    @Test
    fun testTts_differentModels() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val testText = "Testing different models"

        // Test with flash model (low latency)
        val ttsFlash = ElevenLabsTextToSpeechService(
            name = "test-flash",
            context = testContext,
            apiKey = apiKey!!,
            defaultModel = "eleven_flash_v2_5"
        )

        val voices = ttsFlash.listVoices()
        val voiceId = voices.firstOrNull()?.voiceId ?: return@runTest

        val audioFlash = ttsFlash.synthesize(
            text = testText,
            voice = TtsVoiceConfig(voiceId = voiceId),
            options = TtsSynthesisOptions()
        )

        assertTrue(audioFlash.data.size!! > 0, "Flash model should generate audio")
        println("Flash model generated ${audioFlash.data.size} bytes")
    }

    @Test
    fun testTts_voiceSettings() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val voices = tts.listVoices()
        val voiceId = voices.firstOrNull()?.voiceId ?: return@runTest

        // Test with custom voice settings
        val audio = tts.synthesize(
            text = "Testing voice settings",
            voice = TtsVoiceConfig(
                voiceId = voiceId,
                stability = 0.8f,
                similarityBoost = 0.6f,
                style = 0.2f,
                speakerBoost = true
            ),
            options = TtsSynthesisOptions()
        )

        assertTrue(audio.data.size!! > 0, "Should generate audio with custom settings")
    }
}
