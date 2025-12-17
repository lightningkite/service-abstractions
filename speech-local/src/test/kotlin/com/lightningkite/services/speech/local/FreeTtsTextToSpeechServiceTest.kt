package com.lightningkite.services.speech.local

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.speech.TextToSpeechService
import com.lightningkite.services.speech.TtsSynthesisOptions
import com.lightningkite.services.speech.TtsVoiceConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreeTtsTextToSpeechServiceTest {

    private val context = TestSettingContext()

    init {
        // Ensure companion object init block runs to register the URL handler
        FreeTtsTextToSpeechService
    }

    private fun createService(): FreeTtsTextToSpeechService {
        return FreeTtsTextToSpeechService("test-tts", context)
    }

    @Test
    fun `listVoices returns available voices for English`() = runTest {
        val service = createService()
        val voices = service.listVoices("en-US")

        assertTrue(voices.isNotEmpty(), "Should have at least one voice")
        assertTrue(voices.any { it.voiceId == "kevin16" }, "Should have kevin16 voice")
    }

    @Test
    fun `listVoices returns empty for non-English languages`() = runTest {
        val service = createService()
        val voices = service.listVoices("de-DE")

        assertTrue(voices.isEmpty(), "Should not have voices for German")
    }

    @Test
    fun `synthesize generates audio`() = runTest {
        val service = createService()
        val result = service.synthesize(
            text = "Hello, this is a test.",
            voice = TtsVoiceConfig(voiceId = "kevin16"),
            options = TtsSynthesisOptions()
        )

        assertNotNull(result)
        assertTrue(result.data.bytes().isNotEmpty(), "Should generate audio data")
        assertEquals("audio", result.mediaType.type, "Should be audio type")
    }

    @Test
    fun `synthesizeStream emits single chunk`() = runTest {
        val service = createService()
        val chunks = service.synthesizeStream(
            text = "Hello world",
            voice = TtsVoiceConfig(voiceId = "kevin16"),
            options = TtsSynthesisOptions()
        ).toList()

        assertEquals(1, chunks.size, "Should emit exactly one chunk")
        assertTrue(chunks[0].data.bytes().isNotEmpty(), "Chunk should have audio data")
    }

    @Test
    fun `URL scheme freetts is registered`() = runTest {
        val settings = TextToSpeechService.Settings("freetts://")
        val service = settings("test", context)

        assertNotNull(service)
        assertTrue(service is FreeTtsTextToSpeechService)
    }

    @Test
    fun `URL scheme local is registered for TTS`() = runTest {
        val settings = TextToSpeechService.Settings("local://")
        val service = settings("test", context)

        assertNotNull(service)
        assertTrue(service is FreeTtsTextToSpeechService)
    }

    @Test
    fun `healthCheck returns OK`() = runTest {
        val service = createService()
        val status = service.healthCheck()

        assertEquals(com.lightningkite.services.HealthStatus.Level.OK, status.level)
    }
}
