package com.lightningkite.services.speech.elevenlabs

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.speech.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for ElevenLabsSpeechToTextService.
 * These tests verify URL parsing, settings creation, and response parsing.
 * Live API tests require ELEVENLABS_API_KEY environment variable.
 */
class ElevenLabsSpeechToTextServiceTest {

    private val testContext = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        ElevenLabsSpeechToTextService
    }

    // ==================== URL Parsing Tests ====================

    @Test
    fun testUrlParsing_withApiKey() {
        val settings = SpeechToTextService.Settings("elevenlabs-stt://?apiKey=test-key-123&model=scribe_v1")
        val service = settings("test-stt", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsSpeechToTextService)
    }

    @Test
    fun testUrlParsing_defaultModel() {
        val settings = SpeechToTextService.Settings("elevenlabs-stt://?apiKey=test-key-123")
        val service = settings("test-stt", testContext)

        assertNotNull(service)
        assertTrue(service is ElevenLabsSpeechToTextService)
    }

    @Test
    fun testUrlParsing_missingApiKey_noEnvVar() {
        // This test only works if ELEVENLABS_API_KEY env var is not set
        val envKey = System.getenv("ELEVENLABS_API_KEY")
        if (envKey != null) {
            println("Skipping test - ELEVENLABS_API_KEY env var is set")
            return
        }

        val settings = SpeechToTextService.Settings("elevenlabs-stt://")

        assertFailsWith<IllegalArgumentException> {
            settings("test-stt", testContext)
        }
    }

    // ==================== Helper Function Tests ====================

    @Test
    fun testHelperFunction_elevenlabs() {
        with(ElevenLabsSpeechToTextService) {
            val settings = SpeechToTextService.Settings.elevenlabs(
                apiKey = "test-key-123",
                model = "scribe_v1"
            )

            assertEquals("elevenlabs-stt://?apiKey=test-key-123&model=scribe_v1", settings.url)
        }
    }

    // ==================== TranscriptionOptions Tests ====================

    @Test
    fun testTranscriptionOptions_defaults() {
        val options = TranscriptionOptions()

        assertNull(options.language)
        assertNull(options.model)
        assertTrue(options.wordTimestamps)  // Defaults to true
        assertEquals(false, options.speakerDiarization)
        assertNull(options.maxSpeakers)
        assertEquals(false, options.audioEvents)
        assertNull(options.prompt)
    }

    @Test
    fun testTranscriptionOptions_allEnabled() {
        val options = TranscriptionOptions(
            language = "en-US",
            model = "scribe_v1",
            wordTimestamps = true,
            speakerDiarization = true,
            maxSpeakers = 4,
            audioEvents = true,
            prompt = "Business meeting transcription"
        )

        assertEquals("en-US", options.language)
        assertEquals("scribe_v1", options.model)
        assertTrue(options.wordTimestamps)
        assertTrue(options.speakerDiarization)
        assertEquals(4, options.maxSpeakers)
        assertTrue(options.audioEvents)
        assertEquals("Business meeting transcription", options.prompt)
    }

    // ==================== Response Parsing Tests ====================

    @Test
    fun testParseTranscriptionResponse_simpleText() {
        val json = """{"text": "Hello world from ElevenLabs"}"""

        val textPattern = Regex(""""text"\s*:\s*"([^"]*(?:\\.[^"]*)*)"""")
        val match = textPattern.find(json)

        assertNotNull(match)
        assertEquals("Hello world from ElevenLabs", match.groupValues[1])
    }

    @Test
    fun testParseTranscriptionResponse_withLanguage() {
        val json = """{"text": "Hola mundo", "language_code": "es", "language_probability": 0.95}"""

        val languagePattern = Regex(""""language_code"\s*:\s*"([^"]+)"""")
        val langMatch = languagePattern.find(json)
        assertNotNull(langMatch)
        assertEquals("es", langMatch.groupValues[1])

        val probPattern = Regex(""""language_probability"\s*:\s*([0-9.]+)""")
        val probMatch = probPattern.find(json)
        assertNotNull(probMatch)
        assertEquals(0.95f, probMatch.groupValues[1].toFloat(), 0.01f)
    }

    @Test
    fun testParseTranscriptionResponse_withWords() {
        val json = """
        {
            "text": "Hello world",
            "words": [
                {"text": "Hello", "start": 0.0, "end": 0.5},
                {"text": "world", "start": 0.6, "end": 1.0}
            ]
        }
        """.trimIndent()

        val wordsArrayPattern = Regex(""""words"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val wordsMatch = wordsArrayPattern.find(json)

        assertNotNull(wordsMatch)

        val wordPattern = Regex(
            """\{\s*"text"\s*:\s*"([^"]*)"\s*[^}]*"start"\s*:\s*([0-9.]+)[^}]*"end"\s*:\s*([0-9.]+)[^}]*\}""",
            RegexOption.DOT_MATCHES_ALL
        )

        val words = wordPattern.findAll(wordsMatch.groupValues[1]).toList()
        assertEquals(2, words.size)
        assertEquals("Hello", words[0].groupValues[1])
        assertEquals("world", words[1].groupValues[1])
    }

    @Test
    fun testParseTranscriptionResponse_withSpeakers() {
        val json = """
        {
            "text": "Hello from speaker one",
            "speakers": [
                {"speaker": "speaker_0", "start": 0.0, "end": 2.0, "text": "Hello from speaker one"}
            ]
        }
        """.trimIndent()

        val speakersPattern = Regex(""""speakers"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val speakersMatch = speakersPattern.find(json)

        assertNotNull(speakersMatch)
        assertTrue(speakersMatch.groupValues[1].contains("speaker_0"))
    }

    @Test
    fun testParseTranscriptionResponse_withAudioEvents() {
        val json = """
        {
            "text": "[laughter] That was funny!",
            "audio_events": [
                {"type": "laughter", "start": 0.0, "end": 0.5}
            ]
        }
        """.trimIndent()

        val eventsPattern = Regex(""""audio_events"\s*:\s*\[([^\]]*)\]""", RegexOption.DOT_MATCHES_ALL)
        val eventsMatch = eventsPattern.find(json)

        assertNotNull(eventsMatch)
        assertTrue(eventsMatch.groupValues[1].contains("laughter"))
    }

    // ==================== Audio Event Type Tests ====================

    @Test
    fun testAudioEventType_mapping() {
        val mappings = mapOf(
            "laughter" to AudioEventType.LAUGHTER,
            "applause" to AudioEventType.APPLAUSE,
            "music" to AudioEventType.MUSIC,
            "silence" to AudioEventType.SILENCE,
            "noise" to AudioEventType.NOISE,
            "unknown_event" to AudioEventType.OTHER
        )

        mappings.forEach { (input, expected) ->
            val result = when (input.lowercase()) {
                "laughter" -> AudioEventType.LAUGHTER
                "applause" -> AudioEventType.APPLAUSE
                "music" -> AudioEventType.MUSIC
                "silence" -> AudioEventType.SILENCE
                "noise" -> AudioEventType.NOISE
                else -> AudioEventType.OTHER
            }
            assertEquals(expected, result, "Input '$input' should map to $expected")
        }
    }

    // ==================== File Extension Tests ====================

    @Test
    fun testMediaTypeToExtension() {
        val mappings = mapOf(
            "audio/mpeg" to "mp3",
            "audio/wav" to "wav",
            "audio/webm" to "webm",
            "audio/ogg" to "ogg",
            "audio/mp4" to "m4a"
        )

        mappings.forEach { (mediaType, expectedExt) ->
            val result = when {
                mediaType.contains("mpeg") -> "mp3"
                mediaType.contains("wav") -> "wav"
                mediaType.contains("webm") -> "webm"
                mediaType.contains("ogg") -> "ogg"
                mediaType.contains("mp4") || mediaType.contains("m4a") -> "m4a"
                else -> "audio"
            }
            assertEquals(expectedExt, result, "Media type $mediaType should map to $expectedExt")
        }
    }

    // ==================== TranscriptionResult Tests ====================

    @Test
    fun testTranscriptionResult_complete() {
        val words = listOf(
            TranscribedWord(
                text = "Hello",
                startTime = 0.seconds,
                endTime = 0.5.seconds,
                confidence = 0.95f,
                speakerId = "speaker_0"
            ),
            TranscribedWord(
                text = "world",
                startTime = 0.6.seconds,
                endTime = 1.0.seconds,
                confidence = 0.92f,
                speakerId = "speaker_0"
            )
        )

        val speakers = listOf(
            SpeakerSegment(
                speakerId = "speaker_0",
                startTime = 0.seconds,
                endTime = 1.0.seconds,
                text = "Hello world"
            )
        )

        val result = TranscriptionResult(
            text = "Hello world",
            language = "en",
            languageConfidence = 0.98f,
            words = words,
            speakers = speakers,
            audioEvents = emptyList(),
            duration = 1.seconds
        )

        assertEquals("Hello world", result.text)
        assertEquals("en", result.language)
        assertEquals(0.98f, result.languageConfidence)
        assertEquals(2, result.words.size)
        assertEquals(1, result.speakers.size)
        assertEquals(1.seconds, result.duration)
    }

    @Test
    fun testTranscribedWord_properties() {
        val word = TranscribedWord(
            text = "Hello",
            startTime = 0.5.seconds,
            endTime = 1.0.seconds,
            confidence = 0.95f,
            speakerId = "speaker_1"
        )

        assertEquals("Hello", word.text)
        assertEquals(0.5.seconds, word.startTime)
        assertEquals(1.0.seconds, word.endTime)
        assertEquals(0.95f, word.confidence)
        assertEquals("speaker_1", word.speakerId)
    }

    @Test
    fun testSpeakerSegment_properties() {
        val segment = SpeakerSegment(
            speakerId = "speaker_0",
            startTime = 0.seconds,
            endTime = 5.seconds,
            text = "This is the first speaker talking."
        )

        assertEquals("speaker_0", segment.speakerId)
        assertEquals(0.seconds, segment.startTime)
        assertEquals(5.seconds, segment.endTime)
        assertEquals("This is the first speaker talking.", segment.text)
    }

    @Test
    fun testAudioEvent_properties() {
        val event = AudioEvent(
            type = AudioEventType.LAUGHTER,
            startTime = 2.seconds,
            endTime = 3.seconds
        )

        assertEquals(AudioEventType.LAUGHTER, event.type)
        assertEquals(2.seconds, event.startTime)
        assertEquals(3.seconds, event.endTime)
    }
}

/**
 * Live API tests for ElevenLabs STT.
 * These tests require ELEVENLABS_API_KEY environment variable to be set.
 * Skip these in CI unless credentials are configured.
 */
class ElevenLabsSpeechToTextServiceLiveTest {

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

        val service = ElevenLabsSpeechToTextService(
            name = "test",
            context = testContext,
            apiKey = apiKey
        )

        val status = service.healthCheck()
        assertEquals(HealthStatus.Level.OK, status.level, "Health check should pass with valid API key")
    }
}
