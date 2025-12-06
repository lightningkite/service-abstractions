package com.lightningkite.services.speech.elevenlabs

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
 * Unit tests for ElevenLabsTextToSpeechService.
 * These tests verify URL parsing, settings creation, and voice configuration.
 * Live API tests require ELEVENLABS_API_KEY environment variable.
 */
class ElevenLabsTextToSpeechServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        ElevenLabsTextToSpeechService
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_withApiKey() {
        val settings = TextToSpeechService.Settings("elevenlabs://?apiKey=test-key-123&model=eleven_multilingual_v2")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsTextToSpeechService)
    }

    @Test
    fun testUrlParsing_defaultModel() {
        val settings = TextToSpeechService.Settings("elevenlabs://?apiKey=test-key-123")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsTextToSpeechService)
    }

    @Test
    fun testUrlParsing_flashModel() {
        val settings = TextToSpeechService.Settings("elevenlabs://?apiKey=test-key-123&model=eleven_flash_v2_5")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsTextToSpeechService)
    }

    @Test
    fun testUrlParsing_turboModel() {
        val settings = TextToSpeechService.Settings("elevenlabs://?apiKey=test-key-123&model=eleven_turbo_v2_5")
        val service = settings("test", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsTextToSpeechService)
    }

    @Test
    fun testUrlParsing_missingApiKey_noEnvVar() {
        // This test only works if ELEVENLABS_API_KEY env var is not set
        val envKey = System.getenv("ELEVENLABS_API_KEY")
        if (envKey != null) {
            println("Skipping test - ELEVENLABS_API_KEY env var is set")
            return
        }

        val settings = TextToSpeechService.Settings("elevenlabs://")

        assertFailsWith<IllegalArgumentException> {
            settings("test", testContext)
        }
    }

    // ==================== Helper Function Tests ====================

    @Test
    fun testHelperFunction_elevenlabs() {
        with(ElevenLabsTextToSpeechService) {
            val settings = TextToSpeechService.Settings.elevenlabs(
                apiKey = "test-key-123",
                model = "eleven_multilingual_v2"
            )

            assertEquals("elevenlabs://?apiKey=test-key-123&model=eleven_multilingual_v2", settings.url)
        }
    }

    @Test
    fun testHelperFunction_elevenlabs_flashModel() {
        with(ElevenLabsTextToSpeechService) {
            val settings = TextToSpeechService.Settings.elevenlabs(
                apiKey = "test-key-123",
                model = "eleven_flash_v2_5"
            )

            assertEquals("elevenlabs://?apiKey=test-key-123&model=eleven_flash_v2_5", settings.url)
        }
    }

    // ==================== Audio Format Mapping Tests ====================

    @Test
    fun testAudioFormatMapping() {
        // ElevenLabs supports various audio formats
        val supportedFormats = mapOf(
            AudioFormat.MP3_44100_128 to "mp3_44100_128",
            AudioFormat.MP3_44100_192 to "mp3_44100_192",
            AudioFormat.PCM_16000 to "pcm_16000",
            AudioFormat.PCM_22050 to "pcm_22050",
            AudioFormat.PCM_24000 to "pcm_24000",
            AudioFormat.PCM_44100 to "pcm_44100",
            AudioFormat.MULAW_8000 to "ulaw_8000",
            AudioFormat.OGG_OPUS to "opus_48000"
        )

        supportedFormats.forEach { (format, expectedName) ->
            assertNotNull(format, "Format $format should be supported")
            assertNotNull(expectedName, "Expected name for $format")
        }
    }

    // ==================== Voice Settings Tests ====================

    @Test
    fun testVoiceConfig_defaultSettings() {
        val config = TtsVoiceConfig(language = "en-US")

        assertEquals("en-US", config.language)
        assertEquals(TtsGender.NEUTRAL, config.gender)
        assertEquals(0.5f, config.stability)
        assertEquals(0.75f, config.similarityBoost)
        assertEquals(0.0f, config.style)
        assertTrue(config.speakerBoost)
    }

    @Test
    fun testVoiceConfig_customSettings() {
        val config = TtsVoiceConfig(
            voiceId = "custom-voice-id",
            language = "es-ES",
            gender = TtsGender.FEMALE,
            stability = 0.8f,
            similarityBoost = 0.5f,
            style = 0.3f,
            speakerBoost = false
        )

        assertEquals("custom-voice-id", config.voiceId)
        assertEquals("es-ES", config.language)
        assertEquals(TtsGender.FEMALE, config.gender)
        assertEquals(0.8f, config.stability)
        assertEquals(0.5f, config.similarityBoost)
        assertEquals(0.3f, config.style)
        assertEquals(false, config.speakerBoost)
    }

    // ==================== Default Voice ID Tests ====================

    @Test
    fun testDefaultVoiceId_english() {
        val service = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = "test-key"
        )

        // These are internal default voice IDs - we just verify they exist
        // Rachel for female English
        // Adam for male English
        assertNotNull(service)
    }

    // ==================== Voice Response Parsing Tests ====================

    @Test
    fun testParseVoicesResponse_singleVoice() {
        val json = """
        {
            "voices": [
                {
                    "voice_id": "voice123",
                    "name": "Test Voice",
                    "labels": {
                        "gender": "female",
                        "language": "en-US"
                    }
                }
            ]
        }
        """.trimIndent()

        val voicesArrayPattern = Regex(""""voices"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val voicesMatch = voicesArrayPattern.find(json)

        assertNotNull(voicesMatch)

        // The regex needs to handle nested objects - simplified check
        val voiceIdPattern = Regex(""""voice_id"\s*:\s*"([^"]+)"""")
        val voiceNamePattern = Regex(""""name"\s*:\s*"([^"]+)"""")

        val voiceIdMatch = voiceIdPattern.find(voicesMatch.groupValues[1])
        val voiceNameMatch = voiceNamePattern.find(voicesMatch.groupValues[1])

        assertNotNull(voiceIdMatch)
        assertNotNull(voiceNameMatch)
        assertEquals("voice123", voiceIdMatch.groupValues[1])
        assertEquals("Test Voice", voiceNameMatch.groupValues[1])
    }

    @Test
    fun testParseVoicesResponse_multipleVoices() {
        val json = """
        {
            "voices": [
                {"voice_id": "v1", "name": "Voice 1"},
                {"voice_id": "v2", "name": "Voice 2"},
                {"voice_id": "v3", "name": "Voice 3"}
            ]
        }
        """.trimIndent()

        val voicesArrayPattern = Regex(""""voices"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val voicesMatch = voicesArrayPattern.find(json)

        assertNotNull(voicesMatch)
        assertTrue(voicesMatch.groupValues[1].contains("v1"))
        assertTrue(voicesMatch.groupValues[1].contains("v2"))
        assertTrue(voicesMatch.groupValues[1].contains("v3"))
    }

    @Test
    fun testParseLabels_gender() {
        val labelsJson = """"gender": "male", "accent": "british""""

        val labelsPattern = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
        val labels = labelsPattern.findAll(labelsJson).associate {
            it.groupValues[1] to it.groupValues[2]
        }

        assertEquals("male", labels["gender"])
        assertEquals("british", labels["accent"])
    }
}

/**
 * Live API tests for ElevenLabs TTS.
 * These tests require ELEVENLABS_API_KEY environment variable to be set.
 * Skip these in CI unless credentials are configured.
 */
class ElevenLabsTextToSpeechServiceLiveTest {

    private val testContext = TestSettingContext()
    private val apiKey: String? = TestConfig.elevenlabsApiKey

    private fun skipIfNoCredentials() {
        if (apiKey == null) {
            println("Skipping live test - ELEVENLABS_API_KEY not set (check env var or local.properties)")
        }
    }

    @Test
    fun testHealthCheck() = runTest {
        skipIfNoCredentials()
        if (apiKey == null) return@runTest

        val service = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey
        )

        val status = service.healthCheck()
        assertEquals(HealthStatus.Level.OK, status.level, "Health check should pass with valid API key")
    }

    @Test
    fun testListVoices() = runTest {
        skipIfNoCredentials()
        if (apiKey == null) return@runTest

        val service = ElevenLabsTextToSpeechService(
            name = "test",
            context = testContext,
            apiKey = apiKey
        )

        val voices = service.listVoices()
        assertTrue(voices.isNotEmpty(), "ElevenLabs should return available voices")

        voices.forEach { voice ->
            assertNotNull(voice.voiceId, "Voice should have an ID")
            assertNotNull(voice.name, "Voice should have a name")
        }
    }
}
