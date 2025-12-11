package com.lightningkite.services.speech.openai

import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip integration tests for OpenAI TTS + STT.
 *
 * These tests use TTS to generate audio, then STT to transcribe it back.
 * This validates both services work correctly together.
 *
 * Requires OPENAI_API_KEY environment variable to be set.
 */
class OpenAISpeechRoundTripTest {

    private val testContext = TestSettingContext()
    private val apiKey: String? = TestConfig.openaiApiKey

    private fun skipIfNoCredentials(): Boolean {
        if (apiKey == null) {
            println("Skipping live test - OPENAI_API_KEY not set (check env var or local.properties)")
            return true
        }
        return false
    }

    @Test
    fun testTtsHealthCheck() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val status = tts.healthCheck()
        assertEquals(HealthStatus.Level.OK, status.level)
    }

    @Test
    fun testSttHealthCheck() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val stt = OpenAISpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val status = stt.healthCheck()
        assertEquals(HealthStatus.Level.OK, status.level)
    }

    @Test
    fun testRoundTrip_simplePhrase() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val stt = OpenAISpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        // Generate audio from text
        val originalText = "Hello world"
        val audio = tts.synthesize(
            text = originalText,
            voice = TtsVoiceConfig(voiceId = "alloy"),
            options = TtsSynthesisOptions(outputFormat = AudioFormat.MP3_44100_128)
        )

        assertTrue(audio.data.size > 0, "TTS should generate audio data")

        // Transcribe the audio back to text
        val result = stt.transcribe(
            audio = audio,
            options = TranscriptionOptions(language = "en")
        )

        assertTrue(result.text.isNotEmpty(), "STT should return transcription")

        // Check that key words are present (exact match may vary due to speech synthesis)
        val transcribedLower = result.text.lowercase()
        assertTrue(
            transcribedLower.contains("hello") || transcribedLower.contains("world"),
            "Transcription should contain key words. Got: ${result.text}"
        )
    }

    @Test
    fun testRoundTrip_withNumbers() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val stt = OpenAISpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val audio = tts.synthesize(
            text = "The number is one two three",
            voice = TtsVoiceConfig(voiceId = "nova"),
            options = TtsSynthesisOptions()
        )

        val result = stt.transcribe(audio, TranscriptionOptions(language = "en"))

        val transcribedLower = result.text.lowercase()
        assertTrue(
            transcribedLower.contains("one") ||
            transcribedLower.contains("two") ||
            transcribedLower.contains("three") ||
            transcribedLower.contains("123") ||
            transcribedLower.contains("1") ||
            transcribedLower.contains("2") ||
            transcribedLower.contains("3"),
            "Should transcribe numbers. Got: ${result.text}"
        )
    }

    @Test
    fun testRoundTrip_differentVoices() = runTest {
        if (skipIfNoCredentials()) return@runTest

        val tts = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val stt = OpenAISpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey!!
        )

        val voices = listOf("alloy", "echo", "nova")
        val testText = "Testing voice synthesis"

        for (voice in voices) {
            val audio = tts.synthesize(
                text = testText,
                voice = TtsVoiceConfig(voiceId = voice),
                options = TtsSynthesisOptions()
            )

            assertTrue(audio.data.size > 0, "Voice $voice should generate audio")

            val result = stt.transcribe(audio, TranscriptionOptions(language = "en"))
            assertTrue(result.text.isNotEmpty(), "Should transcribe audio from voice $voice")
        }
    }
}
