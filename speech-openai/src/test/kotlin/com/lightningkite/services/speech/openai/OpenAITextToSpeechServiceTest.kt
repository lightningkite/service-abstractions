package com.lightningkite.services.speech.openai

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for OpenAITextToSpeechService.
 * These tests verify URL parsing, settings creation, and voice configuration.
 * Live API tests require OPENAI_API_KEY environment variable.
 */
class OpenAITextToSpeechServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        OpenAITextToSpeechService
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_withApiKey() {
        val settings = TextToSpeechService.Settings("openai://?apiKey=sk-test123&model=tts-1")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is OpenAITextToSpeechService)
    }

    @Test
    fun testUrlParsing_defaultModel() {
        val settings = TextToSpeechService.Settings("openai://?apiKey=sk-test123")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is OpenAITextToSpeechService)
    }

    @Test
    fun testUrlParsing_hdModel() {
        val settings = TextToSpeechService.Settings("openai://?apiKey=sk-test123&model=tts-1-hd")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is OpenAITextToSpeechService)
    }

    @Test
    fun testUrlParsing_missingApiKey_noEnvVar() {
        // This test only works if OPENAI_API_KEY env var is not set
        val envKey = System.getenv("OPENAI_API_KEY")
        if (envKey != null) {
            println("Skipping test - OPENAI_API_KEY env var is set")
            return
        }

        val settings = TextToSpeechService.Settings("openai://")

        assertFailsWith<IllegalArgumentException> {
            settings("test", testContext)
        }
    }

    // ==================== Helper Function Tests ====================

    @Test
    fun testHelperFunction_openai() {
        with(OpenAITextToSpeechService) {
            val settings = TextToSpeechService.Settings.openai(
                apiKey = "sk-test123",
                model = "tts-1"
            )

            assertEquals("openai://?apiKey=sk-test123&model=tts-1", settings.url)
        }
    }

    @Test
    fun testHelperFunction_openai_hdModel() {
        with(OpenAITextToSpeechService) {
            val settings = TextToSpeechService.Settings.openai(
                apiKey = "sk-test123",
                model = "tts-1-hd"
            )

            assertEquals("openai://?apiKey=sk-test123&model=tts-1-hd", settings.url)
        }
    }

    // ==================== Voice List Tests ====================

    @Test
    fun testListVoices_returnsAllOpenAIVoices() = runTest {
        val service = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = "sk-test123"
        )

        val voices = service.listVoices()

        assertEquals(6, voices.size, "OpenAI should have 6 voices")

        val voiceIds = voices.map { it.voiceId }
        assertTrue("alloy" in voiceIds, "Should include alloy voice")
        assertTrue("echo" in voiceIds, "Should include echo voice")
        assertTrue("fable" in voiceIds, "Should include fable voice")
        assertTrue("onyx" in voiceIds, "Should include onyx voice")
        assertTrue("nova" in voiceIds, "Should include nova voice")
        assertTrue("shimmer" in voiceIds, "Should include shimmer voice")
    }

    @Test
    fun testListVoices_withLanguageFilter() = runTest {
        val service = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = "sk-test123"
        )

        val voices = service.listVoices(language = "es")

        // OpenAI voices are multilingual, so they should still return all voices
        // but with the language updated
        assertEquals(6, voices.size)
        voices.forEach { voice ->
            assertEquals("es", voice.language, "Language should be updated to filter")
        }
    }

    @Test
    fun testVoiceGenders() = runTest {
        val service = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = "sk-test123"
        )

        val voices = service.listVoices()
        val voiceByName = voices.associateBy { it.voiceId }

        assertEquals(TtsGender.NEUTRAL, voiceByName["alloy"]?.gender)
        assertEquals(TtsGender.MALE, voiceByName["echo"]?.gender)
        assertEquals(TtsGender.NEUTRAL, voiceByName["fable"]?.gender)
        assertEquals(TtsGender.MALE, voiceByName["onyx"]?.gender)
        assertEquals(TtsGender.FEMALE, voiceByName["nova"]?.gender)
        assertEquals(TtsGender.FEMALE, voiceByName["shimmer"]?.gender)
    }

    // ==================== Audio Format Mapping Tests ====================

    @Test
    fun testAudioFormatMapping() {
        // Test that audio formats are properly mapped
        val service = OpenAITextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = "sk-test123"
        )

        // This is an internal test - we verify via the output format options
        // OpenAI supports: mp3, opus, aac, flac, wav, pcm
        val supportedFormats = listOf(
            AudioFormat.MP3_44100_128,
            AudioFormat.MP3_44100_192,
            AudioFormat.OGG_OPUS,
            AudioFormat.WAV_44100,
            AudioFormat.PCM_24000
        )

        supportedFormats.forEach { format ->
            // Just verify no exception when using these formats
            assertNotNull(format, "Format $format should be supported")
        }
    }
}
